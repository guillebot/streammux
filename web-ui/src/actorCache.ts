import { getCurrentActor } from "./api/activityClient";

let cachedActor: string | null = null;
let actorPromise: Promise<string> | null = null;

export function getCachedActor(): string | null {
  return cachedActor;
}

export async function resolveActor(): Promise<string> {
  if (cachedActor) return cachedActor;
  if (!actorPromise) {
    actorPromise = getCurrentActor()
      .then((actor) => {
        cachedActor = actor;
        return actor;
      })
      .catch(() => "web-ui");
  }
  return actorPromise;
}
