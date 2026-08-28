import { useEffect, useState } from "react";
import { getJobDefinitionSchema } from "./api/client";

// Module-level cache: the schema doesn't change while the app is loaded, and every editor
// mount would otherwise refetch it. One promise, one round-trip, shared across components.
let cachedSchemaPromise: Promise<unknown> | null = null;

function ensureSchema(): Promise<unknown> {
  if (!cachedSchemaPromise) {
    cachedSchemaPromise = getJobDefinitionSchema().catch((error) => {
      // Drop the cached rejected promise so a later mount (after transient failure) can retry.
      cachedSchemaPromise = null;
      throw error;
    });
  }
  return cachedSchemaPromise;
}

export interface UseJobDefinitionSchemaResult {
  schema: unknown | null;
  error: string | null;
}

/**
 * Fetches the JobDefinition JSON Schema once per app session and shares it with any editor
 * mount that needs it. Consumers can render regardless of state — a null schema simply falls
 * back to plain JSON linting.
 */
export function useJobDefinitionSchema(): UseJobDefinitionSchemaResult {
  const [schema, setSchema] = useState<unknown | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    ensureSchema()
      .then((value) => {
        if (!cancelled) setSchema(value);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const message = err instanceof Error ? err.message : String(err);
        setError(message);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return { schema, error };
}
