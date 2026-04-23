import type { JobDefinition } from "./types";

let pending: JobDefinition | null = null;

/** Called before navigating to `/job/new` so the editor picks up the built definition. */
export function stashJobDefinitionForNew(definition: JobDefinition): void {
  pending = definition;
}

/** Returns the stashed definition once, then clears it. */
export function takeStashedJobDefinition(): JobDefinition | null {
  const d = pending;
  pending = null;
  return d;
}
