/**
 * Parser + serializer for the `ROUTE_APP` route filter expression grammar. The
 * canonical implementation is `RouteFilterExpression.java` in `job-contracts`;
 * this TypeScript port keeps the same tokens and precedence so the UI's Basic
 * tab can round-trip filter expressions through a nested-group tree model.
 *
 * Grammar (matching the Java parser):
 *   Or       := And ("||" And)*
 *   And      := Unary ("&&" Unary)*
 *   Unary    := "!" Unary | "(" Or ")" | Comparison
 *   Comparison := Path ("not in" | "in") JsonArray
 *              | Path ("=~" | "!~") JsonString
 *              | Path ("==" | "!=") JsonValue
 *   Path     := "/..." (JSON-pointer style, whitespace-terminated)
 *             | [A-Za-z0-9_.\[\]\-$]+
 */

export type CompareOperator = "==" | "!=" | "in" | "not in" | "=~" | "!~";
export type GroupOperator = "AND" | "OR";

export const COMPARE_OPERATORS: CompareOperator[] = [
  "==",
  "!=",
  "in",
  "not in",
  "=~",
  "!~",
];

export interface FilterRule {
  kind: "rule";
  negated: boolean;
  path: string;
  operator: CompareOperator;
  /** Raw JSON literal text captured from the source, e.g. `"CRITICAL"`, `42`, `["a","b"]`. */
  value: string;
}

export interface FilterGroup {
  kind: "group";
  negated: boolean;
  operator: GroupOperator;
  children: FilterNode[];
}

export type FilterNode = FilterRule | FilterGroup;

export class FilterParseError extends Error {
  readonly position: number;
  constructor(message: string, position: number) {
    super(`${message} at position ${position}`);
    this.name = "FilterParseError";
    this.position = position;
  }
}

// ---- Public API ------------------------------------------------------------

export function emptyFilterGroup(): FilterGroup {
  return { kind: "group", operator: "AND", negated: false, children: [] };
}

export function newRule(): FilterRule {
  return { kind: "rule", negated: false, path: "", operator: "==", value: '""' };
}

/**
 * Parse a route filter expression. A blank string is treated as an empty AND
 * group (the builder's starting point) rather than a parse error; the Java
 * validator handles the blank-input policy separately.
 */
export function parseFilterExpression(text: string): FilterGroup {
  if (!text || text.trim() === "") return emptyFilterGroup();
  const parser = new Parser(text);
  const root = parser.parseExpression();
  parser.expectEnd();
  return normalizeRoot(root);
}

/**
 * Serialize a filter tree to canonical text form. Group children that are
 * themselves groups are always wrapped in parentheses so operator precedence is
 * unambiguous and so the produced text is easy to scan. A root group with a
 * single rule child collapses to just the rule.
 */
export function serializeFilterExpression(root: FilterGroup): string {
  return serializeNode(root, /*isRoot=*/ true);
}

// ---- Value display <-> storage --------------------------------------------
//
// The builder stores `FilterRule.value` as canonical JSON literal text (e.g.
// `"alarm"`, `5`, `["a","b"]`). These helpers present that value without the
// surrounding JSON quotes in the builder inputs and convert typed text back to
// the stored literal. Scalars are always re-encoded as JSON strings; `in` /
// `not in` values are edited as a comma-separated list.

function isArrayOperator(operator: CompareOperator): boolean {
  return operator === "in" || operator === "not in";
}

/** Convert a stored JSON literal to the quote-less text shown in the builder. */
export function ruleValueToDisplay(operator: CompareOperator, value: string): string {
  if (isArrayOperator(operator)) {
    try {
      const parsed = JSON.parse(value);
      if (Array.isArray(parsed)) {
        return parsed.map((item) => scalarToDisplay(item)).join(", ");
      }
      // Non-array scalar (e.g. after an operator switch) shows as a single item.
      return scalarToDisplay(parsed);
    } catch {
      return value;
    }
  }
  try {
    return scalarToDisplay(JSON.parse(value));
  } catch {
    // Bare token that isn't valid JSON — show as-is.
    return value;
  }
}

/** Convert quote-less builder text back to the stored JSON literal. */
export function displayToRuleValue(operator: CompareOperator, input: string): string {
  if (isArrayOperator(operator)) {
    // Comma-separated list. A comma inside a single item is not supported.
    const items = input
      .split(",")
      .map((part) => part.trim())
      .filter((part) => part.length > 0);
    return JSON.stringify(items);
  }
  // Typed scalar text is always stored as a JSON string (quotes escaped).
  return JSON.stringify(input);
}

function scalarToDisplay(parsed: unknown): string {
  return typeof parsed === "string" ? parsed : String(parsed);
}

// ---- Normalization ---------------------------------------------------------

function normalizeRoot(node: FilterNode): FilterGroup {
  if (node.kind === "group") return node;
  return { kind: "group", operator: "AND", negated: false, children: [node] };
}

// ---- Serializer ------------------------------------------------------------

function serializeNode(node: FilterNode, isRoot: boolean): string {
  const core = serializeCore(node, isRoot);
  return node.negated ? `!(${core})` : core;
}

function serializeCore(node: FilterNode, isRoot: boolean): string {
  if (node.kind === "rule") {
    return `${node.path} ${node.operator} ${node.value}`;
  }
  if (node.children.length === 0) return "";
  if (node.children.length === 1) {
    // Collapse a single-child group to its child; the negation wrapper on the
    // group is applied by serializeNode's caller.
    return serializeNode(node.children[0]!, isRoot);
  }
  const opText = node.operator === "AND" ? "&&" : "||";
  const parts = node.children.map((child) => {
    const rendered = serializeNode(child, /*isRoot=*/ false);
    // Wrap non-negated group children in parens so precedence is unambiguous and
    // the emitted text can be re-parsed to an equivalent tree. Negated groups
    // and rules don't need the extra parens (`!(...)` and `a == 1` already stand
    // on their own).
    if (child.kind === "group" && !child.negated && child.children.length > 1) {
      return `(${rendered})`;
    }
    return rendered;
  });
  return parts.join(` ${opText} `);
}

// ---- Parser ----------------------------------------------------------------

const WHITESPACE = /\s/;

function isPathCharacter(ch: string): boolean {
  return /[A-Za-z0-9_.\[\]\-$]/.test(ch);
}

function isValueBoundary(ch: string): boolean {
  return WHITESPACE.test(ch) || ch === ")" || ch === "]" || ch === "&" || ch === "|";
}

function unquote(value: string): string {
  if (value.length >= 2) {
    const first = value.charAt(0);
    const last = value.charAt(value.length - 1);
    if ((first === '"' && last === '"') || (first === "'" && last === "'")) {
      return value.substring(1, value.length - 1);
    }
  }
  return value;
}

class Parser {
  private readonly input: string;
  private index = 0;

  constructor(input: string) {
    this.input = input;
  }

  parseExpression(): FilterNode {
    return this.parseOr();
  }

  expectEnd(): void {
    this.skipWhitespace();
    if (this.index < this.input.length) {
      throw new FilterParseError(
        `unexpected trailing input '${this.input.slice(this.index)}'`,
        this.index,
      );
    }
  }

  private parseOr(): FilterNode {
    let left = this.parseAnd();
    while (true) {
      this.skipWhitespace();
      if (!this.consume("||")) return left;
      const right = this.parseAnd();
      left = mergeGroup("OR", left, right);
    }
  }

  private parseAnd(): FilterNode {
    let left = this.parseUnary();
    while (true) {
      this.skipWhitespace();
      if (!this.consume("&&")) return left;
      const right = this.parseUnary();
      left = mergeGroup("AND", left, right);
    }
  }

  private parseUnary(): FilterNode {
    this.skipWhitespace();
    if (this.consume("!")) {
      const inner = this.parseUnary();
      // If the inner is a single-child parenthesized wrapper, push the negation
      // onto the underlying rule/group so the tree stays compact. Otherwise
      // toggle the inner's own negated flag (double `!` cancels out this way).
      if (
        inner.kind === "group" &&
        !inner.negated &&
        inner.children.length === 1
      ) {
        const child = inner.children[0]!;
        return { ...child, negated: !child.negated };
      }
      return { ...inner, negated: !inner.negated };
    }
    if (this.consume("(")) {
      const inner = this.parseExpression();
      this.skipWhitespace();
      if (!this.consume(")")) {
        throw new FilterParseError("expected ')'", this.index);
      }
      // If the inner is a rule, promote it to a group so downstream code can
      // treat parenthesized rules like any other grouped subexpression.
      return inner.kind === "group"
        ? inner
        : { kind: "group", operator: "AND", negated: false, children: [inner] };
    }
    return this.parseComparison();
  }

  private parseComparison(): FilterRule {
    const path = this.readPath();
    this.skipWhitespace();
    if (this.consume("not in")) {
      const value = this.readJsonArrayText();
      return { kind: "rule", negated: false, path, operator: "not in", value };
    }
    if (this.consume("in")) {
      const value = this.readJsonArrayText();
      return { kind: "rule", negated: false, path, operator: "in", value };
    }
    if (this.consume("=~")) {
      const value = this.readJsonStringText();
      return { kind: "rule", negated: false, path, operator: "=~", value };
    }
    if (this.consume("!~")) {
      const value = this.readJsonStringText();
      return { kind: "rule", negated: false, path, operator: "!~", value };
    }
    if (this.consume("==")) {
      const value = this.readJsonValueText();
      return { kind: "rule", negated: false, path, operator: "==", value };
    }
    if (this.consume("!=")) {
      const value = this.readJsonValueText();
      return { kind: "rule", negated: false, path, operator: "!=", value };
    }
    throw new FilterParseError(
      `expected comparison operator after path '${path}'`,
      this.index,
    );
  }

  private readPath(): string {
    this.skipWhitespace();
    const start = this.index;
    if (this.peek() === "/") {
      this.index++;
      while (
        this.index < this.input.length &&
        !WHITESPACE.test(this.input.charAt(this.index))
      ) {
        this.index++;
      }
    } else {
      while (
        this.index < this.input.length &&
        isPathCharacter(this.input.charAt(this.index))
      ) {
        this.index++;
      }
    }
    if (this.index === start) {
      throw new FilterParseError("expected field path", this.index);
    }
    return this.input.substring(start, this.index);
  }

  private readJsonValueText(): string {
    this.skipWhitespace();
    const parsed = this.readJsonValueAt(this.index);
    if (parsed == null) {
      throw new FilterParseError("expected value", this.index);
    }
    this.index = parsed.end;
    return parsed.text;
  }

  private readJsonArrayText(): string {
    this.skipWhitespace();
    const parsed = this.readJsonValueAt(this.index);
    if (parsed == null) {
      throw new FilterParseError("expected JSON array after 'in'", this.index);
    }
    let arr: unknown;
    try {
      arr = JSON.parse(parsed.text);
    } catch {
      throw new FilterParseError("expected JSON array after 'in'", this.index);
    }
    if (!Array.isArray(arr)) {
      throw new FilterParseError("expected JSON array after 'in'", this.index);
    }
    this.index = parsed.end;
    return parsed.text;
  }

  private readJsonStringText(): string {
    this.skipWhitespace();
    const parsed = this.readJsonValueAt(this.index);
    if (parsed == null) {
      throw new FilterParseError("expected regex string", this.index);
    }
    this.index = parsed.end;
    return parsed.text;
  }

  private readJsonValueAt(start: number): { text: string; end: number } | null {
    if (start >= this.input.length) return null;

    // Grow the slice one character at a time until JSON.parse accepts it AND the
    // next character is a legal value boundary (or we're at EOL). Matches the
    // Java parser's slice-and-verify approach exactly.
    for (let end = start + 1; end <= this.input.length; end++) {
      const slice = this.input.substring(start, end);
      let ok = false;
      try {
        JSON.parse(slice);
        ok = true;
      } catch {
        ok = false;
      }
      if (!ok) continue;
      if (end < this.input.length && !isValueBoundary(this.input.charAt(end))) {
        continue;
      }
      return { text: slice, end };
    }

    // Fallback: treat as an unquoted bare token, matching Java's behaviour of
    // wrapping the token in a TextNode. We emit the JSON-encoded form so the
    // captured value round-trips through JSON.parse cleanly.
    let end = start;
    while (end < this.input.length && !isValueBoundary(this.input.charAt(end))) {
      end++;
    }
    if (end === start) return null;
    const token = this.input.substring(start, end).trim();
    return { text: JSON.stringify(unquote(token)), end };
  }

  private skipWhitespace(): void {
    while (
      this.index < this.input.length &&
      WHITESPACE.test(this.input.charAt(this.index))
    ) {
      this.index++;
    }
  }

  private consume(token: string): boolean {
    this.skipWhitespace();
    if (!this.input.startsWith(token, this.index)) return false;
    const lastChar = token.charAt(token.length - 1);
    const afterIndex = this.index + token.length;
    // Word-token guard, matching the Java parser: if the token ends in a letter
    // (e.g. `in`, `not in`) and the character after it in the source is a valid
    // path character, treat the match as a false positive — otherwise `intField
    // == 1` would gobble `in` as an operator.
    if (
      /[A-Za-z]/.test(lastChar) &&
      afterIndex < this.input.length &&
      isPathCharacter(this.input.charAt(afterIndex))
    ) {
      return false;
    }
    this.index = afterIndex;
    return true;
  }

  private peek(): string {
    this.skipWhitespace();
    return this.index < this.input.length ? this.input.charAt(this.index) : "";
  }
}

function mergeGroup(op: GroupOperator, left: FilterNode, right: FilterNode): FilterGroup {
  // Flatten same-operator, non-negated groups so `a && b && c` becomes one AND
  // group with three children rather than a left-leaning binary tree.
  const leftChildren = extractSameOpChildren(left, op);
  const rightChildren = extractSameOpChildren(right, op);
  return {
    kind: "group",
    operator: op,
    negated: false,
    children: [...leftChildren, ...rightChildren],
  };
}

function extractSameOpChildren(node: FilterNode, op: GroupOperator): FilterNode[] {
  if (node.kind === "group" && node.operator === op && !node.negated) {
    return node.children;
  }
  return [node];
}
