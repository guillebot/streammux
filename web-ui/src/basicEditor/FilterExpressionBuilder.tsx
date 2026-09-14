import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import type { ReactNode } from "react";
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useDroppable,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import type {
  DragEndEvent,
  DragOverEvent,
  DragStartEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  COMPARE_OPERATORS,
  displayToRuleValue,
  FilterParseError,
  parseFilterExpression,
  ruleValueToDisplay,
  serializeFilterExpression,
} from "./filterExpression";
import type { CompareOperator, FilterGroup, GroupOperator } from "./filterExpression";
import {
  containerIdFor,
  duplicateById,
  emptyIdGroup,
  findNodeById,
  findParentAndIndex,
  groupIdFromContainerId,
  hydrateGroup,
  isContainerId,
  isDescendant,
  moveNode,
  newIdRule,
} from "./filterTree";
import type {
  IdFilterGroup,
  IdFilterNode,
  IdFilterRule,
} from "./filterTree";

export interface FilterExpressionBuilderProps {
  value: string;
  onChange: (next: string) => void;
  /** Optional ID prefix so multiple builders on the same page don't collide. */
  idPrefix?: string;
  /** Outer container gets `aria-invalid="true"` and a red frame when true. */
  invalid?: boolean;
  /** Used as the `data-error-path` marker for scroll-to-error targeting. */
  errorScope?: string;
}

/**
 * Nested-group visual builder for `ROUTE_APP` route filter expressions with a
 * freeform text fallback. Text-mode always works; builder mode is only enabled
 * when the current text parses cleanly against `filterExpression`'s grammar.
 *
 * The builder tree carries runtime ids (see `filterTree`) so `@dnd-kit` can
 * key sortable items and so we can move / duplicate nodes across nesting
 * levels. Ids are invisible on the wire — serialization goes through the
 * plain grammar in `filterExpression.ts`.
 */
export function FilterExpressionBuilder({
  value,
  onChange,
  idPrefix = "filter",
  invalid,
  errorScope,
}: FilterExpressionBuilderProps) {
  const [tree, setTree] = useState<IdFilterGroup>(() => safeParse(value).tree);
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
    (nextTree: IdFilterGroup) => {
      setTree(nextTree);
      const nextText = serializeFilterExpression(
        nextTree as unknown as FilterGroup,
      );
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

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  // Live drag state powers the drop indicator and the drag overlay.
  const [activeId, setActiveId] = useState<string | null>(null);
  const [overId, setOverId] = useState<string | null>(null);

  const onDragStart = useCallback((event: DragStartEvent) => {
    setActiveId(String(event.active.id));
  }, []);

  const onDragOver = useCallback((event: DragOverEvent) => {
    setOverId(event.over ? String(event.over.id) : null);
  }, []);

  const clearDrag = useCallback(() => {
    setActiveId(null);
    setOverId(null);
  }, []);

  const onDragEnd = useCallback(
    (event: DragEndEvent) => {
      clearDrag();
      const { active, over } = event;
      if (!over) return;
      const next = moveNode(tree, String(active.id), String(over.id));
      if (next) commitTree(next);
    },
    [tree, commitTree, clearDrag],
  );

  const dndState = useMemo<DndDragState>(
    () => ({ activeId, overId, tree }),
    [activeId, overId, tree],
  );

  const activeNode = activeId ? findNodeById(tree, activeId) : null;

  const canSwitchToBuilder = parseError == null;

  return (
    <div
      className={invalid ? "filter-builder filter-builder-invalid" : "filter-builder"}
      aria-invalid={invalid || undefined}
      data-error-path={errorScope}
    >
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
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          onDragStart={onDragStart}
          onDragOver={onDragOver}
          onDragEnd={onDragEnd}
          onDragCancel={clearDrag}
        >
          <DndDragStateContext.Provider value={dndState}>
            <GroupEditor
              group={tree}
              isRoot
              onChange={commitTree}
              root={tree}
              idPrefix={idPrefix}
            />
          </DndDragStateContext.Provider>
          <DragOverlay dropAnimation={null}>
            {activeNode ? (
              <div className="filter-drag-overlay">{overlayLabel(activeNode)}</div>
            ) : null}
          </DragOverlay>
        </DndContext>
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
  group: IdFilterGroup;
  isRoot: boolean;
  /**
   * Whole-tree setter. Non-root recursion calls it with a mutated *root* tree
   * so id-based operations like duplicate always see the full context.
   */
  onChange: (nextRoot: IdFilterGroup) => void;
  /** Reference to the whole tree — needed by non-root groups to call `duplicateById`. */
  root: IdFilterGroup;
  idPrefix: string;
}

function GroupEditor({ group, isRoot, onChange, root, idPrefix }: GroupEditorProps) {
  const setOperator = (nextOp: GroupOperator) =>
    updateGroupInPlace(root, group.id, (g) => ({ ...g, operator: nextOp }), onChange);
  const toggleNegated = () =>
    updateGroupInPlace(root, group.id, (g) => ({ ...g, negated: !g.negated }), onChange);

  const updateChild = (childId: string, next: IdFilterNode | null) => {
    updateGroupInPlace(
      root,
      group.id,
      (g) => {
        const idx = g.children.findIndex((c) => c.id === childId);
        if (idx < 0) return g;
        const children = g.children.slice();
        if (next == null) children.splice(idx, 1);
        else children[idx] = next;
        return { ...g, children };
      },
      onChange,
    );
  };

  const addRule = () =>
    updateGroupInPlace(
      root,
      group.id,
      (g) => ({ ...g, children: [...g.children, newIdRule()] }),
      onChange,
    );
  const addGroup = () =>
    updateGroupInPlace(
      root,
      group.id,
      (g) => ({ ...g, children: [...g.children, emptyIdGroup()] }),
      onChange,
    );

  const remove = () => onChange(removeGroup(root, group.id));
  const duplicate = () => onChange(duplicateById(root, group.id));

  const childIds = group.children.map((c) => c.id);
  const { setNodeRef: setDroppableRef, isOver } = useDroppable({
    id: containerIdFor(group.id),
  });

  // Highlight this group's card when a drag would land *inside* it (entering
  // from another group), so nesting a rule/group into it reads clearly.
  const { activeId: dragActiveId, overId: dragOverId, tree: dragTree } =
    useContext(DndDragStateContext);
  const isDropTargetGroup = useMemo(() => {
    if (isRoot || !dragActiveId || !dragOverId) return false;
    let targetContainerId: string | null = null;
    if (isContainerId(dragOverId)) {
      targetContainerId = groupIdFromContainerId(dragOverId);
    } else {
      const overPi = findParentAndIndex(dragTree, dragOverId);
      targetContainerId = overPi ? overPi.parent.id : null;
    }
    if (targetContainerId !== group.id) return false;
    // Only when the dragged item is coming from a different group (a real
    // nesting move), and the move wouldn't create a cycle.
    if (isDescendant(dragTree, dragActiveId, group.id)) return false;
    const activePi = findParentAndIndex(dragTree, dragActiveId);
    return activePi ? activePi.parent.id !== group.id : true;
  }, [isRoot, dragActiveId, dragOverId, dragTree, group.id]);

  const header = (
    <div className="filter-group-header">
      {!isRoot ? <DragHandle idPrefix={idPrefix} groupId={group.id} label="Reorder group" /> : null}
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
        {!isRoot ? (
          <>
            <button
              type="button"
              className="filter-duplicate"
              aria-label="Duplicate group"
              title="Duplicate group"
              onClick={duplicate}
            >
              <CopyIcon />
            </button>
            <button
              type="button"
              className="filter-rule-remove danger"
              aria-label="Remove group"
              title="Remove group"
              onClick={remove}
            >
              ×
            </button>
          </>
        ) : null}
      </div>
    </div>
  );

  const body = (
    <>
      {header}
      <SortableContext items={childIds} strategy={verticalListSortingStrategy}>
        <div
          ref={setDroppableRef}
          className={
            isOver && group.children.length === 0
              ? "filter-group-children filter-group-children-empty filter-group-children-over"
              : group.children.length === 0
                ? "filter-group-children filter-group-children-empty"
                : "filter-group-children"
          }
        >
          {group.children.length === 0 ? (
            <p className="muted filter-group-empty">
              No rules yet. Add one with the buttons above, or drop a rule here.
            </p>
          ) : (
            group.children.map((child) => (
              <SortableChild key={child.id} id={child.id}>
                {child.kind === "rule" ? (
                  <RuleEditor
                    rule={child}
                    onChange={(next) => updateChild(child.id, next)}
                    onRemove={() => updateChild(child.id, null)}
                    onDuplicate={() => onChange(duplicateById(root, child.id))}
                  />
                ) : (
                  <GroupEditor
                    group={child}
                    isRoot={false}
                    onChange={onChange}
                    root={root}
                    idPrefix={`${idPrefix}-${child.id}`}
                  />
                )}
              </SortableChild>
            ))
          )}
        </div>
      </SortableContext>
    </>
  );

  const groupClassName = [
    "filter-group",
    isRoot ? "filter-group-root" : "",
    isDropTargetGroup ? "filter-group-drop-target" : "",
  ]
    .filter(Boolean)
    .join(" ");

  return <div className={groupClassName}>{body}</div>;
}

// ---- Sortable wrappers ----------------------------------------------------

function SortableChild({ id, children }: { id: string; children: ReactNode }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id });
  const style: React.CSSProperties = {
    transform: CSS.Translate.toString(transform),
    transition,
  };

  // Decide whether (and where) to draw the insertion indicator for this row.
  const { activeId, overId, tree } = useContext(DndDragStateContext);
  let indicator: "before" | "after" | null = null;
  if (activeId && overId === id && activeId !== id) {
    const activePi = findParentAndIndex(tree, activeId);
    const overPi = findParentAndIndex(tree, id);
    if (activePi && overPi && activePi.parent.id === overPi.parent.id) {
      // Same group: the line goes on the side the item is travelling toward.
      indicator = activePi.index < overPi.index ? "after" : "before";
    } else {
      // Cross-group drops insert before the hovered row.
      indicator = "before";
    }
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={
        isDragging ? "filter-group-child filter-group-child-dragging" : "filter-group-child"
      }
      data-sortable-id={id}
    >
      {indicator === "before" ? (
        <div className="filter-drop-indicator" aria-hidden="true" />
      ) : null}
      <DragListenerContext.Provider value={{ attributes, listeners }}>
        {children}
      </DragListenerContext.Provider>
      {indicator === "after" ? (
        <div className="filter-drop-indicator" aria-hidden="true" />
      ) : null}
    </div>
  );
}

// The drag handle inside a SortableChild reads listeners from context so we
// only attach `useSortable`'s listeners to the handle (not the whole row), and
// so inputs inside a rule row still receive their own pointer events.
interface DragListenerBag {
  attributes: ReturnType<typeof useSortable>["attributes"] | undefined;
  listeners: ReturnType<typeof useSortable>["listeners"] | undefined;
}
const DragListenerContext = createContext<DragListenerBag>({
  attributes: undefined,
  listeners: undefined,
});

// Live drag state shared with every SortableChild so each row can decide
// whether to render the drop indicator and on which side.
interface DndDragState {
  activeId: string | null;
  overId: string | null;
  tree: IdFilterGroup;
}
const DndDragStateContext = createContext<DndDragState>({
  activeId: null,
  overId: null,
  tree: emptyIdGroup(),
});

function DragHandle({
  idPrefix,
  groupId,
  label = "Reorder",
}: {
  idPrefix: string;
  groupId?: string;
  label?: string;
}) {
  const { attributes, listeners } = useContext(DragListenerContext);
  return (
    <button
      type="button"
      className="filter-drag-handle"
      aria-label={label}
      title={label}
      data-testid={groupId ? `${idPrefix}-handle-${groupId}` : undefined}
      {...(attributes ?? {})}
      {...(listeners ?? {})}
    >
      <span aria-hidden="true">⠿</span>
    </button>
  );
}

// ---- Rule editor ----------------------------------------------------------

interface RuleEditorProps {
  rule: IdFilterRule;
  onChange: (next: IdFilterRule) => void;
  onRemove: () => void;
  onDuplicate: () => void;
}

function RuleEditor({ rule, onChange, onRemove, onDuplicate }: RuleEditorProps) {
  const setField = <K extends keyof IdFilterRule>(key: K, next: IdFilterRule[K]) =>
    onChange({ ...rule, [key]: next });

  // Local text for the quote-less value box. Kept separate from rule.value so
  // typing (trailing commas, spaces) isn't normalized mid-edit; it re-derives
  // only when the rule changes from outside our own edits or the operator changes.
  const [valueText, setValueText] = useState<string>(() =>
    ruleValueToDisplay(rule.operator, rule.value),
  );
  const lastEmittedValueRef = useRef<string>(rule.value);
  const lastOperatorRef = useRef<CompareOperator>(rule.operator);

  useEffect(() => {
    const externalValueChange = rule.value !== lastEmittedValueRef.current;
    const operatorChanged = rule.operator !== lastOperatorRef.current;
    if (externalValueChange || operatorChanged) {
      setValueText(ruleValueToDisplay(rule.operator, rule.value));
      lastEmittedValueRef.current = rule.value;
      lastOperatorRef.current = rule.operator;
    }
  }, [rule.value, rule.operator]);

  const onValueTextChange = (input: string) => {
    setValueText(input);
    const encoded = displayToRuleValue(rule.operator, input);
    lastEmittedValueRef.current = encoded;
    setField("value", encoded);
  };

  const onOperatorChange = (nextOp: CompareOperator) => {
    // Re-encode the current display text under the new operator so the stored
    // value stays valid (e.g. == -> in turns `MAJOR` into `["MAJOR"]`).
    const encoded = displayToRuleValue(nextOp, valueText);
    lastEmittedValueRef.current = encoded;
    lastOperatorRef.current = nextOp;
    onChange({ ...rule, operator: nextOp, value: encoded });
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
      <DragHandle idPrefix="rule" label="Reorder rule" />
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
        onChange={(e) => onOperatorChange(e.currentTarget.value as CompareOperator)}
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
        value={valueText}
        onChange={(e) => onValueTextChange(e.currentTarget.value)}
        aria-label="Comparison value"
        aria-invalid={valueError ? true : undefined}
        title={valueError ?? undefined}
      />
      <button
        type="button"
        className="filter-duplicate"
        aria-label="Duplicate rule"
        title="Duplicate rule"
        onClick={onDuplicate}
      >
        <CopyIcon />
      </button>
      <button
        type="button"
        className="filter-rule-remove danger"
        aria-label="Remove rule"
        onClick={onRemove}
      >
        ×
      </button>
    </div>
  );
}

// ---- Tree helpers scoped to this file --------------------------------------

/**
 * Immutably apply `transform` to the group with `groupId` inside `root`, then
 * hand the new root to `onChange`. Keeps the recursion inside `GroupEditor`
 * simple: every mutation is expressed as "update this group's children" and
 * we walk from the root each time so id-based operations always see the whole
 * tree.
 */
function updateGroupInPlace(
  root: IdFilterGroup,
  groupId: string,
  transform: (g: IdFilterGroup) => IdFilterGroup,
  onChange: (next: IdFilterGroup) => void,
) {
  const next = walkAndTransform(root, groupId, transform);
  onChange(next);
}

function walkAndTransform(
  group: IdFilterGroup,
  groupId: string,
  transform: (g: IdFilterGroup) => IdFilterGroup,
): IdFilterGroup {
  if (group.id === groupId) return transform(group);
  let changed = false;
  const children = group.children.map((child) => {
    if (child.kind !== "group") return child;
    const next = walkAndTransform(child, groupId, transform);
    if (next !== child) changed = true;
    return next;
  });
  return changed ? { ...group, children } : group;
}

function removeGroup(root: IdFilterGroup, groupId: string): IdFilterGroup {
  // Find the parent of the target group by walking the tree.
  const removeChild = (g: IdFilterGroup): IdFilterGroup => {
    let changed = false;
    const children: IdFilterNode[] = [];
    for (const child of g.children) {
      if (child.kind === "group" && child.id === groupId) {
        changed = true;
        continue;
      }
      if (child.kind === "group") {
        const next = removeChild(child);
        if (next !== child) changed = true;
        children.push(next);
      } else {
        children.push(child);
      }
    }
    return changed ? { ...g, children } : g;
  };
  return removeChild(root);
}

// ---- Icons -----------------------------------------------------------------

function CopyIcon() {
  return (
    <svg
      viewBox="0 0 16 16"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <rect x="5" y="5" width="8" height="8" rx="1.5" />
      <path d="M3 11V4a1 1 0 0 1 1-1h7" />
    </svg>
  );
}

// ---- Placeholder / value helpers ------------------------------------------

/** Short human label for the item currently being dragged (drag overlay). */
function overlayLabel(node: IdFilterNode): string {
  if (node.kind === "rule") {
    const value = ruleValueToDisplay(node.operator, node.value);
    const parts = [node.negated ? "NOT" : "", node.path || "field", node.operator, value];
    return parts.filter(Boolean).join(" ");
  }
  const count = node.children.length;
  return `${node.negated ? "NOT " : ""}${node.operator} group (${count} item${count === 1 ? "" : "s"})`;
}

function valuePlaceholder(op: CompareOperator): string {
  switch (op) {
    case "in":
    case "not in":
      return "MAJOR, CRITICAL";
    case "=~":
    case "!~":
      return "^prefix.*";
    default:
      return "value";
  }
}

// ---- Utilities ------------------------------------------------------------

function safeParse(text: string): { tree: IdFilterGroup; error: string | null } {
  try {
    return { tree: hydrateGroup(parseFilterExpression(text)), error: null };
  } catch (e) {
    const message = e instanceof FilterParseError ? e.message : e instanceof Error ? e.message : String(e);
    return { tree: emptyIdGroup(), error: message };
  }
}
