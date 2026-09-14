/**
 * Runtime, id-bearing mirror of the filter-expression tree from
 * `filterExpression.ts`, plus pure immutable operations used by the drag-and-
 * drop / duplicate UI in `FilterExpressionBuilder.tsx`.
 *
 * Ids only exist at runtime and are *not* part of the serialized text form:
 * `serializeFilterExpression` reads `path/operator/value/children/negated` and
 * ignores the extra `id` field, so id-bearing trees round-trip cleanly through
 * the on-disk grammar. Keeping ids out of `filterExpression.ts` also lets that
 * module's parser/serializer tests keep comparing plain shapes with `toEqual`.
 */

import type {
  CompareOperator,
  FilterGroup,
  FilterNode,
  FilterRule,
  GroupOperator,
} from "./filterExpression";

// ---- Types -----------------------------------------------------------------

export interface IdFilterRule extends FilterRule {
  id: string;
}

export interface IdFilterGroup {
  kind: "group";
  id: string;
  negated: boolean;
  operator: GroupOperator;
  children: IdFilterNode[];
}

export type IdFilterNode = IdFilterRule | IdFilterGroup;

// ---- Id generation ---------------------------------------------------------

/**
 * Module-level monotonic counter. Ids are unique within the browser tab across
 * all mounted builders, which is all we need — each builder still owns its own
 * `DndContext` so ids from another builder are never in play during a drag.
 *
 * We deliberately avoid `crypto.randomUUID` here so tests running under jsdom
 * (which may not expose `crypto.randomUUID` depending on the version) stay
 * deterministic and cheap.
 */
let idCounter = 0;

export function nextId(): string {
  idCounter += 1;
  return `n${idCounter}`;
}

/** Test-only: reset the id counter so snapshot-style assertions stay stable. */
export function _resetIdCounterForTests(): void {
  idCounter = 0;
}

// ---- Container-id encoding -------------------------------------------------

/**
 * dnd-kit droppable id for a group body (used so empty groups still accept
 * drops). Node ids are `n${n}` and can never collide with this prefix.
 */
const CONTAINER_PREFIX = "container:";

export function containerIdFor(groupId: string): string {
  return `${CONTAINER_PREFIX}${groupId}`;
}

export function isContainerId(id: string): boolean {
  return id.startsWith(CONTAINER_PREFIX);
}

export function groupIdFromContainerId(id: string): string {
  return id.slice(CONTAINER_PREFIX.length);
}

// ---- Hydration -------------------------------------------------------------

/**
 * Deep-copy a plain filter node, assigning fresh ids to every node. Call this
 * after `parseFilterExpression` before feeding the tree into the builder.
 */
export function hydrateIds(node: FilterNode): IdFilterNode {
  if (node.kind === "rule") {
    return { ...node, id: nextId() };
  }
  return {
    kind: "group",
    id: nextId(),
    negated: node.negated,
    operator: node.operator,
    children: node.children.map(hydrateIds),
  };
}

/** Overload-friendly hydrator for the common root-is-group case. */
export function hydrateGroup(group: FilterGroup): IdFilterGroup {
  return hydrateIds(group) as IdFilterGroup;
}

// ---- Lookup ----------------------------------------------------------------

export interface ParentIndex {
  parent: IdFilterGroup;
  index: number;
}

/**
 * Find the parent group and index of `id` under `root`. Returns `null` if the
 * id is not present, or if `id === root.id` (the root has no parent).
 */
export function findParentAndIndex(
  root: IdFilterGroup,
  id: string,
): ParentIndex | null {
  if (root.id === id) return null;
  for (let i = 0; i < root.children.length; i++) {
    const child = root.children[i]!;
    if (child.id === id) return { parent: root, index: i };
    if (child.kind === "group") {
      const found = findParentAndIndex(child, id);
      if (found) return found;
    }
  }
  return null;
}

/** Locate a node by id, searching the whole tree (including the root). */
export function findNodeById(
  root: IdFilterGroup,
  id: string,
): IdFilterNode | null {
  if (root.id === id) return root;
  const pi = findParentAndIndex(root, id);
  if (!pi) return null;
  return pi.parent.children[pi.index] ?? null;
}

/**
 * True when `candidateId` is `ancestorId` itself or is nested somewhere
 * beneath it. Used to block dropping a group into its own subtree.
 */
export function isDescendant(
  root: IdFilterGroup,
  ancestorId: string,
  candidateId: string,
): boolean {
  if (ancestorId === candidateId) return true;
  const ancestor = findNodeById(root, ancestorId);
  if (!ancestor || ancestor.kind !== "group") return false;
  return findParentAndIndex(ancestor, candidateId) != null;
}

// ---- Immutable mutations ---------------------------------------------------

/**
 * Return a copy of `root` with a new `children` array where the child at
 * `index` has been replaced by `mapped(child)`. `mapped` may return `null` to
 * remove the child entirely.
 */
function withReplacedChild(
  group: IdFilterGroup,
  index: number,
  mapped: (child: IdFilterNode) => IdFilterNode | null,
): IdFilterGroup {
  const children = group.children.slice();
  const next = mapped(children[index]!);
  if (next == null) children.splice(index, 1);
  else children[index] = next;
  return { ...group, children };
}

/**
 * Recursively rebuild a tree so that when we hit the group with `groupId`, we
 * call `transform` on that group and return its result. Nodes not on the path
 * to the target are returned by reference.
 */
function updateGroup(
  group: IdFilterGroup,
  groupId: string,
  transform: (g: IdFilterGroup) => IdFilterGroup,
): IdFilterGroup {
  if (group.id === groupId) return transform(group);
  let changed = false;
  const children = group.children.map((child) => {
    if (child.kind !== "group") return child;
    const next = updateGroup(child, groupId, transform);
    if (next !== child) changed = true;
    return next;
  });
  return changed ? { ...group, children } : group;
}

export interface RemoveResult {
  tree: IdFilterGroup;
  removed: IdFilterNode;
}

/**
 * Remove the node with `id` from the tree. Throws if `id === root.id` (root is
 * not removable) or the id is absent. Returns the new tree and the extracted
 * node so callers can re-insert or duplicate it.
 */
export function removeById(root: IdFilterGroup, id: string): RemoveResult {
  if (root.id === id) {
    throw new Error("cannot remove the root group");
  }
  const pi = findParentAndIndex(root, id);
  if (!pi) throw new Error(`filterTree.removeById: id not found: ${id}`);
  const removed = pi.parent.children[pi.index]!;
  const tree = updateGroup(root, pi.parent.id, (g) =>
    withReplacedChild(g, pi.index, () => null),
  );
  return { tree, removed };
}

/**
 * Insert `node` into `containerId` at `index`. `index` is clamped to
 * `[0, children.length]`; `-1` (or `>= length`) appends. Throws if the
 * container id is not a group in the tree.
 */
export function insertInto(
  root: IdFilterGroup,
  containerId: string,
  index: number,
  node: IdFilterNode,
): IdFilterGroup {
  const container = findNodeById(root, containerId);
  if (!container || container.kind !== "group") {
    throw new Error(`filterTree.insertInto: not a group: ${containerId}`);
  }
  return updateGroup(root, containerId, (g) => {
    const children = g.children.slice();
    const clampedIndex =
      index < 0 || index > children.length ? children.length : index;
    children.splice(clampedIndex, 0, node);
    return { ...g, children };
  });
}

/** Immutable array move (dnd-kit `arrayMove` semantics). */
function arrayMove<T>(items: readonly T[], from: number, to: number): T[] {
  const next = items.slice();
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved as T);
  return next;
}

/**
 * Move the node with `activeId` so that it lands at `overId`.
 *
 * `overId` is either:
 * - another node's id — the active node takes that node's slot (sortable
 *   `arrayMove` semantics when in the same group; inserted *before* it when
 *   coming from another group), or
 * - a container id from `containerIdFor(...)` — appended to that group's end
 *   (used when hovering an empty group body).
 *
 * Returns `null` when the move is a no-op or would create a cycle (dropping a
 * group into its own subtree). Callers should treat `null` as "leave the tree
 * as it was".
 */
export function moveNode(
  root: IdFilterGroup,
  activeId: string,
  overId: string,
): IdFilterGroup | null {
  if (activeId === overId) return null;

  const activePi = findParentAndIndex(root, activeId);
  if (!activePi) return null;

  // Case 1: dropped onto a container body (append to that group).
  if (isContainerId(overId)) {
    const targetContainerId = groupIdFromContainerId(overId);
    if (isDescendant(root, activeId, targetContainerId)) return null;
    // Already the last child of this container -> nothing to do.
    if (
      activePi.parent.id === targetContainerId &&
      activePi.index === activePi.parent.children.length - 1
    ) {
      return null;
    }
    const { tree: without, removed } = removeById(root, activeId);
    return insertInto(without, targetContainerId, -1, removed);
  }

  // Case 2: dropped onto another node.
  const overPi = findParentAndIndex(root, overId);
  if (!overPi) return null;
  const targetContainerId = overPi.parent.id;

  // Cycle guard: cannot drop a group into itself or one of its descendants.
  if (isDescendant(root, activeId, targetContainerId)) return null;

  // Same container: reorder with arrayMove so the active node ends up exactly
  // at the over node's slot, regardless of drag direction.
  if (activePi.parent.id === targetContainerId) {
    if (activePi.index === overPi.index) return null;
    return updateGroup(root, targetContainerId, (g) => ({
      ...g,
      children: arrayMove(g.children, activePi.index, overPi.index),
    }));
  }

  // Cross container: remove from the source, then insert *before* the over node
  // at its (recomputed) index in the reduced tree.
  const { tree: without, removed } = removeById(root, activeId);
  const overPiAfter = findParentAndIndex(without, overId);
  if (!overPiAfter) return null;
  return insertInto(without, targetContainerId, overPiAfter.index, removed);
}

/**
 * Deep-clone the node with `id`, assigning fresh ids to every node in the
 * clone, and insert it immediately after the original in its parent. The root
 * itself cannot be duplicated (there's nowhere sensible to put the copy).
 */
export function duplicateById(
  root: IdFilterGroup,
  id: string,
): IdFilterGroup {
  if (root.id === id) {
    throw new Error("cannot duplicate the root group");
  }
  const pi = findParentAndIndex(root, id);
  if (!pi) throw new Error(`filterTree.duplicateById: id not found: ${id}`);
  const original = pi.parent.children[pi.index]!;
  const clone = hydrateIds(stripIdsShallow(original));
  return insertInto(root, pi.parent.id, pi.index + 1, clone);
}

/**
 * Drop all `id` fields from a subtree so `hydrateIds` can reassign fresh ones.
 * We could just walk the id-bearing tree and rewrite ids in place, but going
 * through the id-free shape keeps the hydration path consistent with
 * `parseFilterExpression` output.
 */
function stripIdsShallow(node: IdFilterNode): FilterNode {
  if (node.kind === "rule") {
    const { id: _id, ...rest } = node;
    return rest;
  }
  const { id: _id, children, ...rest } = node;
  return { ...rest, children: children.map(stripIdsShallow) };
}

// ---- Rule/group constructors (id-bearing) ----------------------------------

/** Id-bearing analogue of `emptyFilterGroup` for the builder's initial state. */
export function emptyIdGroup(operator: GroupOperator = "AND"): IdFilterGroup {
  return {
    kind: "group",
    id: nextId(),
    negated: false,
    operator,
    children: [],
  };
}

/** Id-bearing analogue of `newRule` for `+ Rule` button clicks. */
export function newIdRule(): IdFilterRule {
  return {
    kind: "rule",
    id: nextId(),
    negated: false,
    path: "",
    operator: "==" satisfies CompareOperator,
    value: '""',
  };
}
