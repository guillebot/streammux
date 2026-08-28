import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  createCatalogEntry,
  deleteCatalogEntry,
  duplicateCatalogEntry,
  getCatalogEntry,
  pushCatalogEntry,
  updateCatalogEntry,
} from "./api/catalogClient";
import { validateJob } from "./api/client";
import { InlineSpinner } from "./InlineSpinner";
import { JsonEditor } from "./JsonEditor";
import { newJobTemplate } from "./templates";
import type { JobDefinition } from "./types";
import { useJobDefinitionSchema } from "./useJobDefinitionSchema";
import { extractPathFromMessage } from "./validationPathRange";

export function CatalogEditor() {
  const { id: idParam } = useParams();
  const navigate = useNavigate();
  const isNew = idParam === "new";
  const id = isNew ? NaN : Number(idParam);
  const validId = !isNew && Number.isInteger(id);

  const [title, setTitle] = useState("");
  const [jsonText, setJsonTextState] = useState("");
  const [loading, setLoading] = useState(!isNew);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [validation, setValidation] = useState<
    | { kind: "idle" }
    | { kind: "running" }
    | { kind: "ok" }
    | { kind: "fail"; path: string | null; message: string }
  >({ kind: "idle" });

  // Any edit invalidates the last validation result so the squiggle doesn't
  // linger over a field the user has already changed.
  const setJsonText = useCallback((next: string) => {
    setJsonTextState(next);
    setValidation((prev) => (prev.kind === "idle" ? prev : { kind: "idle" }));
  }, []);

  const jsonParseError = useMemo(() => {
    if (!jsonText) return "Fix JSON syntax first";
    try {
      const parsed: unknown = JSON.parse(jsonText);
      if (typeof parsed !== "object" || parsed === null) return "JSON must be an object";
      return null;
    } catch (e) {
      return e instanceof Error ? e.message : "Invalid JSON";
    }
  }, [jsonText]);

  const { schema: jobDefinitionSchema } = useJobDefinitionSchema();

  const load = useCallback(async () => {
    if (!validId) return;
    setLoading(true);
    setError(null);
    try {
      const row = await getCatalogEntry(id);
      setTitle(row.title);
      setJsonText(JSON.stringify(row.payload, null, 2));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [id, validId]);

  useEffect(() => {
    if (isNew) {
      setTitle("");
      setJsonText(JSON.stringify(newJobTemplate(), null, 2));
      setLoading(false);
      return;
    }
    if (validId) void load();
  }, [isNew, validId, load]);

  const parsePayload = (): JobDefinition => {
    const parsed: unknown = JSON.parse(jsonText);
    if (typeof parsed !== "object" || parsed === null) throw new Error("JSON must be a job definition object");
    return parsed as JobDefinition;
  };

  const onValidateConfig = async () => {
    setValidation({ kind: "running" });
    try {
      const payload = parsePayload();
      await validateJob(payload);
      setValidation({ kind: "ok" });
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      const path = extractPathFromMessage(message);
      setValidation({ kind: "fail", path, message });
    }
  };

  const onSave = async () => {
    setNotice(null);
    setError(null);
    try {
      setBusyAction("save");
      const payload = parsePayload();
      // Pre-flight validation against job-management-api so invalid definitions
      // never enter the catalog; the API is the only server-side validator.
      try {
        await validateJob(payload);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        return;
      }
      if (isNew) {
        const created = await createCatalogEntry(title, payload);
        setNotice("Saved to catalog.");
        navigate(`/catalog/items/${created.id}`, { replace: true });
        return;
      }
      if (!validId) return;
      const updated = await updateCatalogEntry(id, title, payload);
      setTitle(updated.title);
      setJsonText(JSON.stringify(updated.payload, null, 2));
      setNotice("Saved to catalog.");
    } catch (e) {
      if (e instanceof SyntaxError) setError(`Invalid JSON: ${e.message}`);
      else setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyAction(null);
    }
  };

  const onDuplicate = async () => {
    if (!validId) return;
    setNotice(null);
    setError(null);
    try {
      setBusyAction("duplicate");
      const created = await duplicateCatalogEntry(id);
      navigate(`/catalog/items/${created.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyAction(null);
    }
  };

  const onDelete = async () => {
    if (!validId) return;
    if (!window.confirm(`Delete catalog entry #${id}?`)) return;
    setNotice(null);
    setError(null);
    try {
      setBusyAction("delete");
      await deleteCatalogEntry(id);
      navigate("/catalog");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyAction(null);
    }
  };

  const onPush = async () => {
    if (!validId) return;
    setNotice(null);
    setError(null);
    try {
      setBusyAction("push");
      // Validate the current editor buffer (may include unsaved edits) before pushing so the
      // user sees the same error they would from Save, without a round-trip through Kafka.
      try {
        const payload = parsePayload();
        await validateJob(payload);
      } catch (e) {
        if (e instanceof SyntaxError) setError(`Invalid JSON: ${e.message}`);
        else setError(e instanceof Error ? e.message : String(e));
        return;
      }
      await pushCatalogEntry(id);
      setNotice("Pushed to job-management-api (POST or PUT).");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusyAction(null);
    }
  };

  if (!isNew && !validId) {
    return <p className="muted">Invalid catalog id.</p>;
  }

  return (
    <div className="page">
      <div className="back-row">
        <Link to="/catalog">← Catalog</Link>
      </div>

      <header className="page-header">
        <h1 className="page-title">{isNew ? "New catalog entry" : `Catalog #${id}`}</h1>
      </header>

      {loading ? <p className="muted">Loading…</p> : null}
      {error ? <div className="banner error">{error}</div> : null}
      {notice ? <div className="banner success">{notice}</div> : null}

      {!loading || isNew ? (
        <>
          <label className="muted" htmlFor="cat-title">
            Title
          </label>
          <input
            id="cat-title"
            type="text"
            className="text-input"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Optional label for this catalog row"
          />

          <label className="muted" htmlFor="cat-json" style={{ display: "block", marginTop: "0.75rem" }}>
            Job definition (JSON)
          </label>
          <JsonEditor
            id="cat-json"
            ariaLabel="Job definition (JSON)"
            value={jsonText}
            onChange={setJsonText}
            schema={jobDefinitionSchema}
            externalDiagnostic={
              validation.kind === "fail"
                ? { path: validation.path, message: validation.message }
                : null
            }
          />

          {validation.kind === "ok" ? (
            <div className="validation-banner ok">Definition is valid.</div>
          ) : validation.kind === "fail" ? (
            <div className="validation-banner fail">{validation.message}</div>
          ) : null}

          <div className="btn-row">
            <button type="button" className="primary" disabled={busyAction !== null} onClick={() => void onSave()}>
              {busyAction === "save" ? (
                <>
                  <InlineSpinner />
                  Saving...
                </>
              ) : (
                "Save to catalog"
              )}
            </button>
            <button
              type="button"
              disabled={
                busyAction !== null || validation.kind === "running" || jsonParseError !== null
              }
              title={jsonParseError ?? undefined}
              onClick={() => void onValidateConfig()}
            >
              {validation.kind === "running" ? (
                <>
                  <InlineSpinner />
                  Validating...
                </>
              ) : (
                "Validate config"
              )}
            </button>
            {!isNew && validId ? (
              <>
                <button type="button" disabled={busyAction !== null} onClick={() => void onDuplicate()}>
                  {busyAction === "duplicate" ? (
                    <>
                      <InlineSpinner />
                      Duplicating...
                    </>
                  ) : (
                    "Duplicate"
                  )}
                </button>
                <button type="button" className="primary" disabled={busyAction !== null} onClick={() => void onPush()}>
                  {busyAction === "push" ? (
                    <>
                      <InlineSpinner />
                      Pushing...
                    </>
                  ) : (
                    "Push to API"
                  )}
                </button>
                <button type="button" className="danger" disabled={busyAction !== null} onClick={() => void onDelete()}>
                  {busyAction === "delete" ? (
                    <>
                      <InlineSpinner />
                      Deleting...
                    </>
                  ) : (
                    "Delete from catalog"
                  )}
                </button>
              </>
            ) : null}
          </div>
        </>
      ) : null}
    </div>
  );
}
