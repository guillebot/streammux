import { useEffect, useState } from "react";
import { getKafkaTopicCatalog } from "../api/client";
import {
  JOB_BUILDER_FALLBACK_INPUT_TOPICS,
  JOB_BUILDER_FALLBACK_OUTPUT_TOPICS,
} from "../jobBuilderOptions";

export interface TopicCatalogState {
  inputTopics: string[];
  outputTopics: string[];
  loading: boolean;
  error: string | null;
}

interface Catalog {
  inputTopics: string[];
  outputTopics: string[];
}

// Module-level cache so the Basic tab (which unmounts on every tab switch) reuses
// a single fetch instead of hitting the broker each time the panel mounts.
let cache: Catalog | null = null;
let inflight: Promise<Catalog> | null = null;

function load(): Promise<Catalog> {
  if (cache) return Promise.resolve(cache);
  if (!inflight) {
    inflight = getKafkaTopicCatalog()
      .then((catalog) => {
        const result: Catalog = {
          inputTopics:
            catalog.inputTopics.length > 0
              ? catalog.inputTopics
              : JOB_BUILDER_FALLBACK_INPUT_TOPICS,
          outputTopics:
            catalog.outputTopics.length > 0
              ? catalog.outputTopics
              : JOB_BUILDER_FALLBACK_OUTPUT_TOPICS,
        };
        cache = result;
        return result;
      })
      .catch((e) => {
        // Drop the failed promise so a later mount can retry.
        inflight = null;
        throw e;
      });
  }
  return inflight;
}

/**
 * Fetches the broker topic catalog once (shared across mounts) and exposes it for
 * topic comboboxes. Falls back to the Job Builder fallback lists on error so the
 * editor keeps working offline; `allowCustom` on the combobox still lets users
 * enter topics that aren't in the list.
 */
export function useTopicCatalog(): TopicCatalogState {
  const [state, setState] = useState<TopicCatalogState>(() => ({
    inputTopics: cache?.inputTopics ?? JOB_BUILDER_FALLBACK_INPUT_TOPICS,
    outputTopics: cache?.outputTopics ?? JOB_BUILDER_FALLBACK_OUTPUT_TOPICS,
    loading: cache == null,
    error: null,
  }));

  useEffect(() => {
    if (cache) return;
    let cancelled = false;
    setState((s) => ({ ...s, loading: true, error: null }));
    load()
      .then((res) => {
        if (cancelled) return;
        setState({
          inputTopics: res.inputTopics,
          outputTopics: res.outputTopics,
          loading: false,
          error: null,
        });
      })
      .catch((e) => {
        if (cancelled) return;
        setState((s) => ({
          ...s,
          loading: false,
          error: e instanceof Error ? e.message : String(e),
        }));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return state;
}
