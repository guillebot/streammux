// SVG icons specific to the filter-expression builder's action buttons
// (duplicate, add rule, add group). Shared app-wide action icons such as the
// trash icon live in `../jobActionIcons`.

/** "Duplicate" — overlapping squares (copy). */
export function IconCopy() {
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

/** "Add rule" — list-plus (lucide). */
export function IconRulePlus() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      focusable={false}
    >
      <path d="M11 12H3" />
      <path d="M16 6H3" />
      <path d="M16 18H3" />
      <path d="M18 9v6" />
      <path d="M21 12h-6" />
    </svg>
  );
}

/** "Add group" — a plus followed by a pair of parentheses, mirroring how a
 *  group serializes in the expression, e.g. `(a && b)`. */
export function IconGroupPlus() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      focusable={false}
    >
      <path d="M5 9v6" />
      <path d="M2 12h6" />
      <path d="M13 5c-2.5 3.5 -2.5 10.5 0 14" />
      <path d="M19 5c2.5 3.5 2.5 10.5 0 14" />
    </svg>
  );
}
