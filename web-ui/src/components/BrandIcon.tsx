import type { SVGProps } from "react";

const iconProps: SVGProps<SVGSVGElement> = {
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.75,
  strokeLinecap: "round",
  strokeLinejoin: "round",
  "aria-hidden": true,
  focusable: false,
};

/** Router / mux icon: one input stream splitting through a device to multiple outputs. */
export function BrandIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <svg {...iconProps} {...props}>
      <rect x="8" y="7.5" width="8" height="9" rx="1.5" />
      <path d="M3 12h5" />
      <path d="M16 9.5c2.5-.5 4.5-2 5-4.5" />
      <path d="M16 12h5" />
      <path d="M16 14.5c2.5.5 4.5 2 5 4.5" />
      <circle cx="3" cy="12" r="1" fill="currentColor" stroke="none" />
      <circle cx="21" cy="5" r="1" fill="currentColor" stroke="none" />
      <circle cx="21" cy="12" r="1" fill="currentColor" stroke="none" />
      <circle cx="21" cy="19" r="1" fill="currentColor" stroke="none" />
    </svg>
  );
}
