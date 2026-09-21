import { parsePathSteps } from "../validationPathRange";

/**
 * Utilities that map a server-side validation error path (as produced by
 * {@link ../validationPathRange#extractPathFromMessage}) to Basic-form field
 * identifiers, so the Basic tab can highlight the offending field and scroll
 * it into view when the user clicks "Validate config".
 */

/**
 * Rewrite a validation error path into a canonical form the Basic form uses
 * for match-checking: dotted property access with numeric bracket indices,
 * no `$.` prefix, and no whitespace. Returns `null` when the path is missing
 * or cannot be parsed.
 */
export function normalizeErrorPath(path: string | null | undefined): string | null {
  if (!path) return null;
  const steps = parsePathSteps(path);
  if (!steps || steps.length === 0) return null;
  let out = "";
  for (const step of steps) {
    if (step.kind === "prop") {
      out += out.length === 0 ? step.key : `.${step.key}`;
    } else {
      out += `[${step.index}]`;
    }
  }
  return out;
}

/** Exact-match: does the error land on this exact field? */
export function isErrorOnField(
  errorPath: string | null | undefined,
  field: string | null | undefined,
): boolean {
  if (!errorPath || !field) return false;
  return errorPath === field;
}

/**
 * Prefix-match: does the error land somewhere at or under `scope`? Useful for
 * container-level highlighting (labels editor, routes list, etc.) when the
 * error path points deep into a nested field.
 */
export function isErrorUnderField(
  errorPath: string | null | undefined,
  scope: string | null | undefined,
): boolean {
  if (!errorPath || !scope) return false;
  if (errorPath === scope) return true;
  return errorPath.startsWith(`${scope}.`) || errorPath.startsWith(`${scope}[`);
}
