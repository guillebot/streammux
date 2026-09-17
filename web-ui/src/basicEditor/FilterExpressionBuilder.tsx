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
  getFirstCollision,
  pointerWithin,
  rectIntersection,
  useDroppable,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import type {
  CollisionDetection,
  DragEndEvent,
  DragStartEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { IconTrash } from "../jobActionIcons";
import { IconCopy, IconGroupPlus, IconRulePlus } from "./filterBuilderIcons";
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
  const [overSide, setOverSide] = useState<DropSide>(null);
  // The resolved drop target for the current pointer position. Computed in
  // `collisionDetection` (where the live pointer is available) and mirrored into
  // React state on drag-move so the indicator can render.
  const dropRef = useRef<{ overId: string; side: DropSide } | null>(null);

  // Custom collision resolution for the nested tree. Beyond dnd-kit's built-ins
  // it does two things that plain closestCenter can't:
  //  1. When a group *body* is hovered, resolve down to the closest child row
  //     (so reordering inside a group targets a specific rule and shows a line
  //     there); empty groups keep the container so they still accept drops.
  //  2. Compute the before/after edge from the real pointer, then "escalate"
  //     outward past a trailing/leading group edge — this is what lets you land
  //     as a sibling *after* (or *before*) a group instead of being trapped as
  //     its last/first child when there is no row beyond it to aim at.
  const collisionDetection = useCallback<CollisionDetection>(
    (args) => {
      const { droppableRects, pointerCoordinates } = args;
      const pointer = pointerWithin(args);
      const intersections = pointer.length > 0 ? pointer : rectIntersection(args);
      let resolved = getFirstCollision(intersections, "id");
      if (resolved == null) resolved = getFirstCollision(closestCenter(args), "id");
      if (resolved == null) {
        dropRef.current = null;
        return [];
      }
      if (typeof resolved === "string" && isContainerId(resolved)) {
        const group = findNodeById(tree, groupIdFromContainerId(resolved));
        if (group && group.kind === "group" && group.children.length > 0) {
          const childIds = new Set(group.children.map((c) => c.id));
          const inner = getFirstCollision(
            closestCenter({
              ...args,
              droppableContainers: args.droppableContainers.filter(
                (c) => c.id !== resolved && childIds.has(String(c.id)),
              ),
            }),
            "id",
          );
          if (inner != null) resolved = inner;
        }
      }

      let resolvedId = String(resolved);
      let side: DropSide = null;
      if (!isContainerId(resolvedId) && pointerCoordinates) {
        const rect = droppableRects.get(resolvedId);
        side =
          rect && pointerCoordinates.y > rect.top + rect.height / 2
            ? "after"
            : "before";
        // The group that currently owns the dragged item. We never escalate out
        // of it: doing so would turn an in-group reorder to the first/last slot
        // into a jump outside the group (the reported bug). Items coming from a
        // *different* group can still escalate to sit before/after this group.
        const activeParentId =
          findParentAndIndex(tree, String(args.active.id))?.parent.id ?? null;
        // Escalate outward while the resolved node is the last/first child of a
        // non-root group and the pointer is beyond that child's *outer* edge
        // (below the last row / above the first row). The child's own body is
        // deliberately left alone so the first/last slot *inside* the group
        // stays reachable when dragging an item in from elsewhere. Stops once
        // the node's parent is the root (root children are already the outermost
        // sibling level). The indicator follows each step, so the line climbs to
        // the group's outer edge as you drag past it.
        // eslint-disable-next-line no-constant-condition
        while (true) {
          const pi = findParentAndIndex(tree, resolvedId);
          if (!pi || pi.parent.id === tree.id) break;
          if (pi.parent.id === activeParentId) break;
          const nodeRect = droppableRects.get(resolvedId);
          if (!nodeRect) break;
          const isLast = pi.index === pi.parent.children.length - 1;
          const isFirst = pi.index === 0;
          if (side === "after" && isLast && pointerCoordinates.y >= nodeRect.bottom) {
            resolvedId = pi.parent.id;
            continue;
          }
          if (side === "before" && isFirst && pointerCoordinates.y <= nodeRect.top) {
            resolvedId = pi.parent.id;
            continue;
          }
          break;
        }
      }

      dropRef.current = {
        overId: resolvedId,
        side: isContainerId(resolvedId) ? null : side,
      };
      return [{ id: resolvedId }];
    },
    [tree],
  );

  const onDragStart = useCallback((event: DragStartEvent) => {
    setActiveId(String(event.active.id));
  }, []);

  // Mirror the ref (set during collision detection) into state. Runs on every
  // move so the indicator flips sides as the pointer crosses a row's midpoint,
  // even while the `over` droppable itself hasn't changed.
  const syncDropTarget = useCallback(() => {
    const d = dropRef.current;
    const nextOver = d?.overId ?? null;
    const nextSide = d?.side ?? null;
    setOverId((prev) => (prev === nextOver ? prev : nextOver));
    setOverSide((prev) => (prev === nextSide ? prev : nextSide));
  }, []);

  const clearDrag = useCallback(() => {
    setActiveId(null);
    setOverId(null);
    setOverSide(null);
    dropRef.current = null;
  }, []);

  const onDragEnd = useCallback(
    (event: DragEndEvent) => {
      const target = dropRef.current;
      clearDrag();
      if (!target) return;
      const next = moveNode(
        tree,
        String(event.active.id),
        target.overId,
        target.side ?? undefined,
      );
      if (next) commitTree(next);
    },
    [tree, commitTree, clearDrag],
  );

  const dndState = useMemo<DndDragState>(
    () => ({ activeId, overId, overSide, tree }),
    [activeId, overId, overSide, tree],
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
          collisionDetection={collisionDetection}
          onDragStart={onDragStart}
          onDragMove={syncDropTarget}
          onDragOver={syncDropTarget}
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
        <button
          type="button"
          className="icon-btn"
          aria-label="Add rule"
          title="Add rule"
          onClick={addRule}
        >
          <IconRulePlus />
        </button>
        <button
          type="button"
          className="icon-btn"
          aria-label="Add group"
          title="Add group"
          onClick={addGroup}
        >
          <IconGroupPlus />
        </button>
        {!isRoot ? (
          <>
            <button
              type="button"
              className="icon-btn"
              aria-label="Duplicate group"
              title="Duplicate group"
              onClick={duplicate}
            >
              <IconCopy />
            </button>
            <button
              type="button"
              className="icon-btn danger"
              aria-label="Remove group"
              title="Remove group"
              onClick={remove}
            >
              <IconTrash />
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
  // Rows stay put during a drag: the explicit drop indicator plus the
  // DragOverlay chip convey placement. We intentionally ignore useSortable's
  // reorder transform — applying it shifts siblings while @dnd-kit still
  // hit-tests against their pre-drag rects, so drops (worst when dragging
  // downward) landed a full row past where the cursor showed.
  const { attributes, listeners, setNodeRef, isDragging } = useSortable({ id });

  // Draw the insertion indicator on the edge the pointer is closest to, as
  // computed live in `onDragOver`. This row owns the line only while it is the
  // resolved drop target.
  const { activeId, overId, overSide } = useContext(DndDragStateContext);
  const indicator: "before" | "after" | null =
    activeId && overId === id && activeId !== id ? (overSide ?? "before") : null;

  return (
    <div
      ref={setNodeRef}
      className={
        isDragging ? "filter-group-child filter-group-child-dragging" : "filter-group-child"
      }
      data-sortable-id={id}
    >
      {indicator === "before" ? (
        <div className="filter-drop-indicator filter-drop-indicator-before" aria-hidden="true" />
      ) : null}
      <DragListenerContext.Provider value={{ attributes, listeners }}>
        {children}
      </DragListenerContext.Provider>
      {indicator === "after" ? (
        <div className="filter-drop-indicator filter-drop-indicator-after" aria-hidden="true" />
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

type DropSide = "before" | "after" | null;

// Live drag state shared with every SortableChild so each row can decide
// whether to render the drop indicator and on which side.
interface DndDragState {
  activeId: string | null;
  overId: string | null;
  overSide: DropSide;
  tree: IdFilterGroup;
}
const DndDragStateContext = createContext<DndDragState>({
  activeId: null,
  overId: null,
  overSide: null,
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
        className="icon-btn"
        aria-label="Duplicate rule"
        title="Duplicate rule"
        onClick={onDuplicate}
      >
        <IconCopy />
      </button>
      <button
        type="button"
        className="icon-btn danger"
        aria-label="Remove rule"
        title="Remove rule"
        onClick={onRemove}
      >
        <IconTrash />
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
