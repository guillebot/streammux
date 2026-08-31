import { describe, expect, it } from "vitest";
import {
  emptyFilterGroup,
  FilterParseError,
  parseFilterExpression,
  serializeFilterExpression,
} from "./filterExpression";
import type { FilterGroup } from "./filterExpression";

function roundTrip(text: string): string {
  return serializeFilterExpression(parseFilterExpression(text));
}

describe("parseFilterExpression", () => {
  it("returns an empty AND group for blank input", () => {
    expect(parseFilterExpression("")).toEqual(emptyFilterGroup());
    expect(parseFilterExpression("   ")).toEqual(emptyFilterGroup());
  });

  it("parses a single equality rule", () => {
    const tree = parseFilterExpression('type == "alarm"');
    expect(tree).toEqual({
      kind: "group",
      operator: "AND",
      negated: false,
      children: [
        {
          kind: "rule",
          negated: false,
          path: "type",
          operator: "==",
          value: '"alarm"',
        },
      ],
    });
  });

  it("parses a numeric equality without quoting", () => {
    const tree = parseFilterExpression("severity == 5");
    expect(tree.children[0]).toMatchObject({
      kind: "rule",
      path: "severity",
      operator: "==",
      value: "5",
    });
  });

  it("parses inequality, in, not in, and regex operators", () => {
    const kinds = [
      ['type != "clear"', "!=", '"clear"'],
      ['severity in ["MAJOR","CRITICAL"]', "in", '["MAJOR","CRITICAL"]'],
      ['severity not in ["INFO"]', "not in", '["INFO"]'],
      ['message =~ "^ALARM_.*"', "=~", '"^ALARM_.*"'],
      ['message !~ "test"', "!~", '"test"'],
    ] as const;
    for (const [source, op, val] of kinds) {
      const tree = parseFilterExpression(source);
      expect(tree.children[0]).toMatchObject({ kind: "rule", operator: op, value: val });
    }
  });

  it("flattens same-operator chains into a single group", () => {
    const tree = parseFilterExpression("a == 1 && b == 2 && c == 3");
    expect(tree.operator).toBe("AND");
    expect(tree.children).toHaveLength(3);
  });

  it("honours precedence: && binds tighter than ||", () => {
    const tree = parseFilterExpression("a == 1 || b == 2 && c == 3");
    expect(tree.operator).toBe("OR");
    expect(tree.children).toHaveLength(2);
    const [first, second] = tree.children;
    expect(first).toMatchObject({ kind: "rule", path: "a" });
    expect(second).toMatchObject({ kind: "group", operator: "AND" });
    if (second && second.kind === "group") {
      expect(second.children.map((c) => (c.kind === "rule" ? c.path : null))).toEqual([
        "b",
        "c",
      ]);
    }
  });

  it("handles explicit parentheses that override precedence", () => {
    const tree = parseFilterExpression("(a == 1 || b == 2) && c == 3");
    expect(tree.operator).toBe("AND");
    expect(tree.children).toHaveLength(2);
    expect(tree.children[0]).toMatchObject({ kind: "group", operator: "OR" });
  });

  it("propagates a leading ! as a group negation on the root group", () => {
    const tree = parseFilterExpression('!(a == 1 && b == 2)');
    expect(tree.negated).toBe(true);
    expect(tree.operator).toBe("AND");
    expect(tree.children).toHaveLength(2);
    expect(tree.children[0]).toMatchObject({ kind: "rule", path: "a" });
    expect(tree.children[1]).toMatchObject({ kind: "rule", path: "b" });
  });

  it("propagates a leading ! on a bare rule as a rule negation", () => {
    const tree = parseFilterExpression('!(type == "alarm")');
    expect(tree.negated).toBe(false);
    expect(tree.children).toHaveLength(1);
    expect(tree.children[0]).toMatchObject({
      kind: "rule",
      negated: true,
      path: "type",
      operator: "==",
      value: '"alarm"',
    });
  });

  it("does not treat 'in'/'not in' as operators when followed by a path character", () => {
    const tree = parseFilterExpression('intField == 3');
    expect(tree.children[0]).toMatchObject({ path: "intField", operator: "==" });
  });

  it("rejects malformed expressions", () => {
    expect(() => parseFilterExpression("a ==")).toThrow(FilterParseError);
    expect(() => parseFilterExpression("&& a == 1")).toThrow(FilterParseError);
    expect(() => parseFilterExpression("(a == 1")).toThrow(FilterParseError);
    expect(() => parseFilterExpression('severity in "not-array"')).toThrow(FilterParseError);
  });

  it("accepts bracketed and dotted paths", () => {
    const tree = parseFilterExpression('event.severity == "CRITICAL"');
    expect(tree.children[0]).toMatchObject({ path: "event.severity" });
    const tree2 = parseFilterExpression('event[0].kind == "x"');
    expect(tree2.children[0]).toMatchObject({ path: "event[0].kind" });
  });

  it("accepts JSON-pointer style paths", () => {
    const tree = parseFilterExpression('/event/severity == "CRITICAL"');
    expect(tree.children[0]).toMatchObject({ path: "/event/severity" });
  });
});

describe("serializeFilterExpression", () => {
  it("serializes an empty group to the empty string", () => {
    expect(serializeFilterExpression(emptyFilterGroup())).toBe("");
  });

  it("collapses a single-child root group to just the child", () => {
    const tree: FilterGroup = {
      kind: "group",
      operator: "AND",
      negated: false,
      children: [
        { kind: "rule", negated: false, path: "a", operator: "==", value: "1" },
      ],
    };
    expect(serializeFilterExpression(tree)).toBe("a == 1");
  });

  it("wraps non-negated multi-child group children in parentheses", () => {
    const tree: FilterGroup = {
      kind: "group",
      operator: "OR",
      negated: false,
      children: [
        { kind: "rule", negated: false, path: "a", operator: "==", value: "1" },
        {
          kind: "group",
          operator: "AND",
          negated: false,
          children: [
            { kind: "rule", negated: false, path: "b", operator: "==", value: "2" },
            { kind: "rule", negated: false, path: "c", operator: "==", value: "3" },
          ],
        },
      ],
    };
    expect(serializeFilterExpression(tree)).toBe("a == 1 || (b == 2 && c == 3)");
  });

  it("renders a negated group with the !(...) wrapper", () => {
    const tree: FilterGroup = {
      kind: "group",
      operator: "AND",
      negated: false,
      children: [
        {
          kind: "group",
          operator: "AND",
          negated: true,
          children: [
            { kind: "rule", negated: false, path: "a", operator: "==", value: "1" },
            { kind: "rule", negated: false, path: "b", operator: "==", value: "2" },
          ],
        },
      ],
    };
    expect(serializeFilterExpression(tree)).toBe("!(a == 1 && b == 2)");
  });
});

describe("round-trip parse ∘ serialize", () => {
  const cases: string[] = [
    "a == 1",
    'type == "alarm"',
    'severity in ["MAJOR","CRITICAL"]',
    'severity not in ["INFO"]',
    'msg =~ "^ALARM"',
    'msg !~ "test"',
    "a == 1 && b == 2",
    "a == 1 || b == 2",
    "a == 1 && b == 2 && c == 3",
    "a == 1 || (b == 2 && c == 3)",
    "(a == 1 || b == 2) && c == 3",
    "!(a == 1 && b == 2)",
    '/event/severity == "CRITICAL"',
    "event.severity == 5",
  ];

  for (const source of cases) {
    it(`round-trips: ${source}`, () => {
      const first = roundTrip(source);
      const second = roundTrip(first);
      // Fixed-point: serialize(parse(serialize(parse(x)))) === serialize(parse(x))
      expect(second).toBe(first);
    });
  }
});
