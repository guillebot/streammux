import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ChangeEvent } from "react";
import {
  COMPARE_OPERATORS,
  emptyFilterGroup,
  FilterParseError,
  newRule,
  parseFilterExpression,
  serializeFilterExpression,
} from "./filterExpression";
import type {
  CompareOperator,
  FilterGroup,
  FilterNode,
  FilterRule,
  GroupOperator,
} from "./filterExpression";

export interface FilterExpressionBuilderProps {
  value: string;
  onChange: (next: string) => void;
  /** Optional ID prefix so multiple builders on the same page don't collide. */
  idPrefix?: string;
}

/**
 * Nested-group visual builder for `ROUTE_APP` route filter expressions with a
 * freeform text fallback. Text-mode always works; builder mode is only enabled
 * when the current text parses cleanly against `filterExpression`'s grammar.
 */
export function FilterExpressionBuilder({
  value,
  onChange,
  idPrefix = "filter",
}: FilterExpressionBuilderProps) {
  const [tree, setTree] = useState<FilterGroup>(() => safeParse(value).tree);
  const [parseError, setParseError] = useState<string | null>(
    () => safeParse(value).error,
  );
  const [mode, setMode] = useState<"builder" | "text">(
    () => (safeParse(value).error ? "text" : "builder"),
  );
  const lastEmittedRef = useRef<string>(value);

  useEffect(() => {
    if (value === lastEmittedRef.current) return;
    const parsed = safeParse(value);
    setTree(parsed.tree);
    setParseError(parsed.error);
    if (parsed.error) setMode("text");
    lastEmittedRef.current = value;
  }, [value]);

  const commitTree = useCallback(
    (nextTree: FilterGroup) => {
      setTree(nextTree);
      const nextText = serializeFilterExpression(nextTree);
      lastEmittedRef.current = nextText;
      setParseError(null);
      onChange(nextText);
    },
    [onChange],
  );

  const commitText = useCallback(
    (nextText: string) => {
      lastEmittedRef.current = nextText;
      const parsed = safeParse(nextText);
      setTree(parsed.tree);
      setParseError(parsed.error);
      onChange(nextText);
    },
    [onChange],
  );

  const canSwitchToBuilder = parseError == null;

  return (
    <div className="filter-builder">
      <div className="filter-mode-toggle">
        <button
          type="button"
          className={mode === "builder" ? "filter-mode-active" : ""}
          disabled={!canSwitchToBuilder}
          title={canSwitchToBuilder ? undefined : (parseError ?? undefined)}
          onClick={() => setMode("builder")}
        >
          Builder
        </button>
        <button
          type="button"
          className={mode === "text" ? "filter-mode-active" : ""}
          onClick={() => setMode("text")}
        >
          Text
        </button>
      </div>
      {parseError ? (
        <p className="filter-parse-error" role="alert">
          Can't visualize this expression: {parseError}. Edit the text below or fix
          the syntax to re-enable the builder.
        </p>
      ) : null}
      {mode === "builder" ? (
        <GroupEditor
          group={tree}
          isRoot
          onChange={commitTree}
          idPrefix={idPrefix}
        />
      ) : (
        <textarea
          className="text-input mono filter-textarea"
          rows={2}
          spellCheck={false}
          autoComplete="off"
          value={value}
          onChange={(e) => commitText(e.currentTarget.value)}
          placeholder='e.g. type == "alarm" && severity in ["MAJOR","CRITICAL"]'
        />
      )}
    </div>
  );
}

// ---- Group editor (recursive) ---------------------------------------------

interface GroupEditorProps {
  group: FilterGroup;
  isRoot: boolean;
  onChange: (next: FilterGroup) => void;
  onRemove?: () => void;
  idPrefix: string;
}

function GroupEditor({ group, isRoot, onChange, onRemove, idPrefix }: GroupEditorProps) {
  const setOperator = (nextOp: GroupOperator) =>
    onChange({ ...group, operator: nextOp });
  const toggleNegated = () => onChange({ ...group, negated: !group.negated });

  const updateChild = (index: number, next: FilterNode | null) => {
    const children = group.children.slice();
    if (next == null) children.splice(index, 1);
    else children[index] = next;
    onChange({ ...group, children });
  };

  const addRule = () =>
    onChange({ ...group, children: [...group.children, newRule()] });
  const addGroup = () =>
    onChange({
      ...group,
      children: [...group.children, emptyFilterGroup()],
    });

  return (
    <div className={isRoot ? "filter-group filter-group-root" : "filter-group"}>
      <div className="filter-group-header">
        <label className="filter-negate">
          <input
            type="checkbox"
            checked={group.negated}
            onChange={toggleNegated}
            aria-label="Negate group"
          />
          <span>NOT</span>
        </label>
        <select
          className="select-inline filter-op-select"
          value={group.operator}
          onChange={(e) => setOperator(e.currentTarget.value as GroupOperator)}
          aria-label="Group operator"
        >
          <option value="AND">AND</option>
          <option value="OR">OR</option>
        </select>
        <div className="filter-group-header-actions">
          <button type="button" onClick={addRule}>
            + Rule
          </button>
          <button type="button" onClick={addGroup}>
            + Group
          </button>
          {!isRoot && onRemove ? (
            <button
              type="button"
              className="danger"
              onClick={onRemove}
              aria-label="Remove group"
            >
              Remove group
            </button>
          ) : null}
        </div>
      </div>
      {group.children.length === 0 ? (
        <p className="muted filter-group-empty">
          No rules yet. Add one with the buttons above.
        </p>
      ) : (
        <div className="filter-group-children">
          {group.children.map((child, i) => (
            <div key={i} className="filter-group-child">
              {child.kind === "rule" ? (
                <RuleEditor
                  rule={child}
                  onChange={(next) => updateChild(i, next)}
                  onRemove={() => updateChild(i, null)}
                />
              ) : (
                <GroupEditor
                  group={child}
                  isRoot={false}
                  onChange={(next) => updateChild(i, next)}
                  onRemove={() => updateChild(i, null)}
                  idPrefix={`${idPrefix}-${i}`}
                />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ---- Rule editor ----------------------------------------------------------

interface RuleEditorProps {
  rule: FilterRule;
  onChange: (next: FilterRule) => void;
  onRemove: () => void;
}

function RuleEditor({ rule, onChange, onRemove }: RuleEditorProps) {
  const setField = <K extends keyof FilterRule>(key: K, next: FilterRule[K]) =>
    onChange({ ...rule, [key]: next });

  const onValueChange = (e: ChangeEvent<HTMLInputElement>) => {
    setField("value", e.currentTarget.value);
  };

  const valueError = useMemo(() => {
    const text = rule.value.trim();
    if (text === "") return "Value is empty";
    try {
      const parsed = JSON.parse(text);
      if (rule.operator === "in" || rule.operator === "not in") {
        if (!Array.isArray(parsed)) return "Value must be a JSON array for 'in' / 'not in'";
      }
      if (rule.operator === "=~" || rule.operator === "!~") {
        if (typeof parsed !== "string") return "Regex operators require a JSON string";
      }
      return null;
    } catch {
      // Unquoted bare token is still accepted by the underlying parser as a text
      // fallback, so we treat non-JSON as a soft warning rather than an error.
      if (rule.operator === "in" || rule.operator === "not in") {
        return "Value must be a JSON array for 'in' / 'not in'";
      }
      return null;
    }
  }, [rule.value, rule.operator]);

  return (
    <div className="filter-rule-row">
      <label className="filter-negate">
        <input
          type="checkbox"
          checked={rule.negated}
          onChange={(e) => setField("negated", e.currentTarget.checked)}
          aria-label="Negate rule"
        />
        <span>NOT</span>
      </label>
      <input
        className="text-input filter-path-input"
        type="text"
        autoComplete="off"
        spellCheck={false}
        placeholder="path.to.field"
        value={rule.path}
        onChange={(e) => setField("path", e.currentTarget.value)}
        aria-label="Field path"
      />
      <select
        className="select-inline filter-op-select"
        value={rule.operator}
        onChange={(e) => setField("operator", e.currentTarget.value as CompareOperator)}
        aria-label="Comparison operator"
      >
        {COMPARE_OPERATORS.map((op) => (
          <option key={op} value={op}>
            {op}
          </option>
        ))}
      </select>
      <input
        className={
          valueError ? "text-input mono filter-value-input invalid" : "text-input mono filter-value-input"
        }
        type="text"
        autoComplete="off"
        spellCheck={false}
        placeholder={valuePlaceholder(rule.operator)}
        value={rule.value}
        onChange={onValueChange}
        aria-label="Comparison value (JSON literal)"
        aria-invalid={valueError ? true : undefined}
        title={valueError ?? undefined}
      />
      <button
        type="button"
        className="filter-rule-remove"
        aria-label="Remove rule"
        onClick={onRemove}
      >
        ×
      </button>
    </div>
  );
}

function valuePlaceholder(op: CompareOperator): string {
  switch (op) {
    case "in":
    case "not in":
      return '["MAJOR","CRITICAL"]';
    case "=~":
    case "!~":
      return '"^prefix.*"';
    default:
      return '"value"';
  }
}

// ---- Utilities ------------------------------------------------------------

function safeParse(text: string): { tree: FilterGroup; error: string | null } {
  try {
    return { tree: parseFilterExpression(text), error: null };
  } catch (e) {
    const message = e instanceof FilterParseError ? e.message : e instanceof Error ? e.message : String(e);
    return { tree: emptyFilterGroup(), error: message };
  }
}
