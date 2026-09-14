import { beforeEach, describe, expect, it } from "vitest";
import {
  parseFilterExpression,
  serializeFilterExpression,
} from "./filterExpression";
import type { FilterGroup } from "./filterExpression";
import {
  _resetIdCounterForTests,
  containerIdFor,
  duplicateById,
  findNodeById,
  findParentAndIndex,
  hydrateGroup,
  hydrateIds,
  isDescendant,
  moveNode,
  nextId,
  removeById,
} from "./filterTree";
import type { IdFilterGroup, IdFilterNode } from "./filterTree";

beforeEach(() => {
  _resetIdCounterForTests();
});

/** Round-trip via serialize + parse: shape-equivalence check that ignores ids. */
function serializeIds(tree: IdFilterGroup): string {
  return serializeFilterExpression(tree as unknown as FilterGroup);
}

function sampleTree(): IdFilterGroup {
  // ( type == "alarm" && ( severity in ["MAJOR","CRITICAL"] || source == "sensor-1" ) )
  return hydrateGroup(
    parseFilterExpression(
      'type == "alarm" && (severity in ["MAJOR","CRITICAL"] || source == "sensor-1")',
    ),
  );
}

describe("nextId", () => {
  it("emits monotonically increasing ids after reset", () => {
    expect(nextId()).toBe("n1");
    expect(nextId()).toBe("n2");
  });
});

describe("hydrateIds", () => {
  it("preserves serialized output (ids are invisible on the wire)", () => {
    const source = 'type == "alarm" && severity in ["MAJOR","CRITICAL"]';
    const before = serializeFilterExpression(parseFilterExpression(source));
    const hydrated = hydrateGroup(parseFilterExpression(source));
    expect(serializeIds(hydrated)).toBe(before);
  });

  it("gives every node a unique id", () => {
    const tree = sampleTree();
    const ids = new Set<string>();
    const walk = (n: IdFilterNode) => {
      expect(ids.has(n.id)).toBe(false);
      ids.add(n.id);
      if (n.kind === "group") n.children.forEach(walk);
    };
    walk(tree);
    // root + AND + rule "type" + nested OR group + rule "severity" + rule "source"
    expect(ids.size).toBeGreaterThanOrEqual(4);
  });
});

describe("findParentAndIndex / findNodeById", () => {
  it("returns null for the root id", () => {
    const tree = sampleTree();
    expect(findParentAndIndex(tree, tree.id)).toBeNull();
  });

  it("locates a nested child by id", () => {
    const tree = sampleTree();
    const nested = tree.children[1] as IdFilterGroup;
    const inner = nested.children[0]!;
    const found = findParentAndIndex(tree, inner.id);
    expect(found?.parent.id).toBe(nested.id);
    expect(found?.index).toBe(0);
    expect(findNodeById(tree, inner.id)).toBe(inner);
  });
});

describe("isDescendant", () => {
  it("treats a node as its own descendant", () => {
    const tree = sampleTree();
    expect(isDescendant(tree, tree.id, tree.id)).toBe(true);
  });

  it("detects nested containment", () => {
    const tree = sampleTree();
    const nested = tree.children[1] as IdFilterGroup;
    const innerRule = nested.children[0]!;
    expect(isDescendant(tree, nested.id, innerRule.id)).toBe(true);
    expect(isDescendant(tree, innerRule.id, nested.id)).toBe(false);
  });
});

describe("removeById", () => {
  it("removes a leaf rule and returns it", () => {
    const tree = sampleTree();
    const target = tree.children[0]!;
    const { tree: next, removed } = removeById(tree, target.id);
    expect(removed).toBe(target);
    expect(next.children).toHaveLength(1);
    // parents on the untouched path stay referentially equal.
    expect(next.children[0]).toBe(tree.children[1]);
  });

  it("throws when asked to remove the root", () => {
    const tree = sampleTree();
    expect(() => removeById(tree, tree.id)).toThrow(/root/i);
  });
});

describe("moveNode within a group", () => {
  it("reorders siblings downward (drop onto a lower slot)", () => {
    const tree = hydrateGroup(
      parseFilterExpression('a == "1" && b == "2" && c == "3"'),
    );
    const [a, b, c] = tree.children;
    // Drag `a` down onto `c`'s slot -> `a` lands where `c` was (arrayMove).
    const next = moveNode(tree, a!.id, c!.id);
    expect(next).not.toBeNull();
    const ids = next!.children.map((n) => n.id);
    expect(ids).toEqual([b!.id, c!.id, a!.id]);
  });

  it("reorders siblings upward (drop onto a higher slot)", () => {
    const tree = hydrateGroup(
      parseFilterExpression('a == "1" && b == "2" && c == "3"'),
    );
    const [a, b, c] = tree.children;
    // Drag `c` up onto `a`'s slot -> `c` lands first.
    const next = moveNode(tree, c!.id, a!.id);
    expect(next).not.toBeNull();
    const ids = next!.children.map((n) => n.id);
    expect(ids).toEqual([c!.id, a!.id, b!.id]);
  });

  it("moves down by a single slot (regression: was a no-op)", () => {
    const tree = hydrateGroup(
      parseFilterExpression('a == "1" && b == "2" && c == "3"'),
    );
    const [a, b, c] = tree.children;
    // Drag `a` onto the adjacent `b` -> they swap.
    const next = moveNode(tree, a!.id, b!.id);
    expect(next).not.toBeNull();
    const ids = next!.children.map((n) => n.id);
    expect(ids).toEqual([b!.id, a!.id, c!.id]);
  });

  it("is a no-op when the source is dropped onto itself", () => {
    const tree = sampleTree();
    const rule = tree.children[0]!;
    expect(moveNode(tree, rule.id, rule.id)).toBeNull();
  });
});

describe("moveNode across groups", () => {
  it("moves a rule from the root into a nested group by container id", () => {
    const tree = sampleTree();
    const topRule = tree.children[0]!; // `type == "alarm"`
    const nested = tree.children[1] as IdFilterGroup;
    const next = moveNode(tree, topRule.id, containerIdFor(nested.id));
    expect(next).not.toBeNull();
    expect(next!.children).toHaveLength(1);
    const movedNested = next!.children[0] as IdFilterGroup;
    expect(movedNested.children).toHaveLength(3);
    expect(movedNested.children[2]!.id).toBe(topRule.id);
  });

  it("moves a rule out of a nested group by dropping onto a root sibling", () => {
    const tree = sampleTree();
    const nested = tree.children[1] as IdFilterGroup;
    const innerRule = nested.children[0]!; // severity in [...]
    const topRule = tree.children[0]!; // type == "alarm"
    const next = moveNode(tree, innerRule.id, topRule.id);
    expect(next).not.toBeNull();
    // innerRule now sits at the root at index 0, topRule shifts to index 1.
    expect(next!.children[0]!.id).toBe(innerRule.id);
    expect(next!.children[1]!.id).toBe(topRule.id);
    const remainingNested = next!.children[2] as IdFilterGroup;
    expect(remainingNested.children.map((c) => c.id)).not.toContain(
      innerRule.id,
    );
  });

  it("blocks moving a group into its own descendant", () => {
    const tree = sampleTree();
    const nested = tree.children[1] as IdFilterGroup;
    const innerRule = nested.children[0]!;
    // Try to drop the nested group into one of its own children — should be
    // refused because that would create a cycle.
    expect(moveNode(tree, nested.id, innerRule.id)).toBeNull();
    expect(moveNode(tree, nested.id, containerIdFor(nested.id))).toBeNull();
  });
});

describe("duplicateById", () => {
  it("clones a rule with a fresh id immediately after the original", () => {
    const tree = hydrateGroup(
      parseFilterExpression('a == "1" && b == "2"'),
    );
    const b = tree.children[1]!;
    const next = duplicateById(tree, b.id);
    expect(next.children).toHaveLength(3);
    const clone = next.children[2]!;
    expect(clone.id).not.toBe(b.id);
    // Cloned rule has the same visible content.
    expect(clone.kind).toBe("rule");
    if (clone.kind === "rule" && b.kind === "rule") {
      expect(clone.path).toBe(b.path);
      expect(clone.operator).toBe(b.operator);
      expect(clone.value).toBe(b.value);
    }
  });

  it("deep-clones a nested group with fresh ids on every descendant", () => {
    const tree = sampleTree();
    const nested = tree.children[1] as IdFilterGroup;
    const next = duplicateById(tree, nested.id);
    const clone = next.children[2] as IdFilterGroup;
    expect(clone.id).not.toBe(nested.id);
    expect(clone.children.map((c) => c.id)).not.toEqual(
      nested.children.map((c) => c.id),
    );
    // Serialized form of the whole tree gains a repeated subexpression.
    const before = serializeIds(tree);
    const after = serializeIds(next);
    const sub = "severity in";
    const beforeCount = before.split(sub).length - 1;
    const afterCount = after.split(sub).length - 1;
    expect(afterCount).toBe(beforeCount + 1);
  });

  it("throws when asked to duplicate the root", () => {
    const tree = sampleTree();
    expect(() => duplicateById(tree, tree.id)).toThrow(/root/i);
  });
});

describe("hydrateIds preserves shape", () => {
  it("via a round-trip: parse -> hydrate -> serialize equals parse -> serialize", () => {
    const cases = [
      'type == "alarm"',
      'type == "alarm" && severity in ["MAJOR","CRITICAL"]',
      '!(a == "1") || (b == "2" && c != "3")',
      "",
    ];
    for (const src of cases) {
      const plain = serializeFilterExpression(parseFilterExpression(src));
      const withIds = serializeIds(hydrateGroup(parseFilterExpression(src)));
      expect(withIds).toBe(plain);
    }
  });
});

describe("hydrateIds on a plain rule", () => {
  it("returns an id-bearing rule shape", () => {
    const node = hydrateIds({
      kind: "rule",
      negated: false,
      path: "a",
      operator: "==",
      value: '"x"',
    });
    expect(node.kind).toBe("rule");
    expect(node.id).toMatch(/^n\d+$/);
  });
});
