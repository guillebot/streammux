import { jsonLanguage } from "@codemirror/lang-json";
import type { SyntaxNode } from "@lezer/common";

/**
 * Utilities for translating a server-side validation error message like
 * `routeAppConfig.routes[0].filterExpression invalid: ...` into a text range
 * inside the current JSON document, so CodeMirror can squiggle the exact field
 * that failed validation.
 *
 * The path parser also accepts a leading `$.` so it works for the schema-layer
 * errors (`$.jobId: string expected`) as well as the semantic-layer errors that
 * {@link ../../../../job-contracts/src/main/java/io/github/guillebot/streammux/contracts/validation/JobDefinitionValidator.java}
 * emits.
 */

type PathStep = { kind: "prop"; key: string } | { kind: "index"; index: number };

const LEADING_PATH_TOKEN = /^\$?\.?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*|\[[^\]]+\])*)/;

// Words that both semantic and schema validators use right after the path.
// See JobDefinitionValidator.java and JobDefinitionSchemaProvider.java errors.
const VALIDATION_VERBS = new Set(["is", "must", "invalid", "cannot", "should", "does"]);

/**
 * Extracts the leading dotted/bracketed path token from a validation error
 * message. Returns null when the message doesn't look like it has a leading
 * path (e.g. "route outputTopic is required" or "deleted jobs must include...").
 *
 * A leading token only counts as a path when it is followed by a known
 * validation verb (`is required`, `must be...`, `invalid: ...`) or by a colon
 * (schema-layer style, `$.jobId: string expected`). Two-word prefixes that
 * look identifier-like but are really English (e.g. "deleted jobs must ...")
 * are rejected on purpose.
 */
export function extractPathFromMessage(message: string): string | null {
  if (!message) return null;
  const match = LEADING_PATH_TOKEN.exec(message);
  if (!match) return null;
  const path = match[1];
  if (!path) return null;
  const remainder = message.slice(match[0].length);
  if (remainder.length === 0) return path;
  if (remainder.startsWith(":")) return path;
  const nextWordMatch = /^\s+([A-Za-z]+)/.exec(remainder);
  if (!nextWordMatch) return null;
  if (!VALIDATION_VERBS.has(nextWordMatch[1])) return null;
  return path;
}

/**
 * Splits a dotted/bracketed path like `routeAppConfig.routes[0].filterExpression`
 * into an ordered list of property/index steps. Bracketed segments containing
 * only digits are treated as array indices; anything else is treated as a
 * property name (so `mappings[myMapping]` still resolves).
 */
export function parsePathSteps(path: string): PathStep[] | null {
  if (!path) return null;
  const steps: PathStep[] = [];
  let i = 0;
  const trimmed = path.replace(/^\$\.?/, "");
  while (i < trimmed.length) {
    if (trimmed[i] === ".") {
      i++;
      continue;
    }
    if (trimmed[i] === "[") {
      const end = trimmed.indexOf("]", i + 1);
      if (end < 0) return null;
      const inside = trimmed.slice(i + 1, end);
      if (/^\d+$/.test(inside)) {
        steps.push({ kind: "index", index: Number(inside) });
      } else {
        steps.push({ kind: "prop", key: unquoteBracketKey(inside) });
      }
      i = end + 1;
      continue;
    }
    let end = i;
    while (end < trimmed.length && trimmed[end] !== "." && trimmed[end] !== "[") {
      end++;
    }
    const key = trimmed.slice(i, end);
    if (!key) return null;
    steps.push({ kind: "prop", key });
    i = end;
  }
  return steps.length > 0 ? steps : null;
}

function unquoteBracketKey(raw: string): string {
  const trimmed = raw.trim();
  if (trimmed.length >= 2) {
    const first = trimmed[0];
    const last = trimmed[trimmed.length - 1];
    if ((first === '"' && last === '"') || (first === "'" && last === "'")) {
      return trimmed.slice(1, -1);
    }
  }
  return trimmed;
}

/**
 * Resolves a validator path against the given JSON text and returns the
 * character range (inclusive `from`, exclusive `to`) of the value node the
 * path points at. Returns null when the path cannot be located — for instance
 * when the offending field is missing entirely (a valid "required" error) or
 * when the JSON is not parseable.
 */
export function resolveJsonPathRange(
  text: string,
  path: string,
): { from: number; to: number } | null {
  const steps = parsePathSteps(path);
  if (!steps) return null;
  const tree = jsonLanguage.parser.parse(text);
  const top = firstValueChild(tree.topNode);
  if (!top) return null;

  let current: SyntaxNode | null = top;
  for (const step of steps) {
    if (!current) return null;
    if (step.kind === "prop") {
      current = findPropertyValue(current, text, step.key);
    } else {
      current = findArrayItem(current, step.index);
    }
  }
  if (!current) return null;
  return { from: current.from, to: current.to };
}

function firstValueChild(node: SyntaxNode): SyntaxNode | null {
  let child = node.firstChild;
  while (child && (child.type.isError || child.name === "⚠")) {
    child = child.nextSibling;
  }
  return child;
}

function findPropertyValue(
  objectNode: SyntaxNode,
  text: string,
  key: string,
): SyntaxNode | null {
  if (objectNode.name !== "Object") return null;
  let prop = objectNode.firstChild;
  while (prop) {
    if (prop.name === "Property") {
      const nameNode = prop.getChild("PropertyName");
      if (nameNode) {
        const raw = text.slice(nameNode.from, nameNode.to);
        if (decodeJsonString(raw) === key) {
          // The value is the last child of the Property node.
          const value = prop.lastChild;
          if (value && value.name !== "PropertyName") {
            return value;
          }
        }
      }
    }
    prop = prop.nextSibling;
  }
  return null;
}

function findArrayItem(arrayNode: SyntaxNode, index: number): SyntaxNode | null {
  if (arrayNode.name !== "Array") return null;
  let child = arrayNode.firstChild;
  let seen = 0;
  while (child) {
    if (isValueNode(child)) {
      if (seen === index) return child;
      seen++;
    }
    child = child.nextSibling;
  }
  return null;
}

function isValueNode(node: SyntaxNode): boolean {
  switch (node.name) {
    case "Object":
    case "Array":
    case "String":
    case "Number":
    case "True":
    case "False":
    case "Null":
      return true;
    default:
      return false;
  }
}

function decodeJsonString(raw: string): string {
  if (raw.length < 2 || raw[0] !== '"' || raw[raw.length - 1] !== '"') {
    return raw;
  }
  try {
    return JSON.parse(raw) as string;
  } catch {
    return raw.slice(1, -1);
  }
}
