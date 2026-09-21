import { useEffect, useMemo, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { json, jsonLanguage, jsonParseLinter } from "@codemirror/lang-json";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { linter, lintGutter, type Diagnostic } from "@codemirror/lint";
import { EditorView, hoverTooltip } from "@codemirror/view";
import { tags as t } from "@lezer/highlight";
import {
  handleRefresh,
  jsonCompletion,
  jsonSchemaHover,
  jsonSchemaLinter,
  stateExtensions,
} from "codemirror-json-schema";
import { resolveJsonPathRange } from "./validationPathRange";

export interface JsonEditorProps {
  value: string;
  onChange: (value: string) => void;
  id?: string;
  ariaLabel?: string;
  className?: string;
  minHeight?: number;
  /**
   * When true the editor grows to fill the remaining viewport height, keeping
   * `minHeight` as a floor. The actual height is driven by the
   * `.json-editor--fill` CSS rule; here we just let CodeMirror stretch to 100%.
   */
  fillViewport?: boolean;
  /**
   * Optional JSON Schema. When provided, the editor gains schema-aware linting, hover
   * tooltips, and autocompletion in addition to plain JSON syntax linting. Passing null or
   * undefined leaves the editor with syntax-only linting so callers can render before their
   * schema fetch resolves without any visible degradation.
   */
  schema?: unknown | null;
  /**
   * Optional server-side validation failure to overlay as a CodeMirror error
   * diagnostic. When `path` resolves to a value inside the current JSON the
   * squiggle lands on that exact range; otherwise it falls back to the whole
   * document so the user still sees a visible error.
   */
  externalDiagnostic?: { path: string | null; message: string } | null;
}

type Scheme = "light" | "dark";

function preferredScheme(): Scheme {
  if (typeof window === "undefined" || !window.matchMedia) return "dark";
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

// Colors are driven by the app's CSS variables (see index.css :root and the
// prefers-color-scheme override) so the editor auto-flips with the rest of the
// UI. Numbers get a warm hex because there's no existing palette token that
// reads sensibly in both schemes.
const jsonHighlight = HighlightStyle.define([
  { tag: t.propertyName, color: "var(--accent)" },
  { tag: t.string, color: "var(--ok)" },
  { tag: t.number, color: "#c98a6b" },
  { tag: [t.bool, t.null], color: "var(--danger)" },
  { tag: [t.brace, t.squareBracket, t.separator, t.punctuation], color: "var(--muted)" },
  { tag: t.invalid, color: "var(--danger)" },
]);

function buildUiTheme(dark: boolean) {
  return EditorView.theme(
    {
      "&": {
        backgroundColor: "var(--surface)",
        color: "var(--text)",
        fontSize: "0.85rem",
      },
      ".cm-scroller": {
        fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
      },
      ".cm-content": {
        caretColor: "var(--accent)",
      },
      ".cm-cursor, .cm-dropCursor": {
        borderLeftColor: "var(--accent)",
      },
      "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection": {
        backgroundColor: "color-mix(in srgb, var(--accent) 30%, transparent)",
      },
      ".cm-activeLine": {
        backgroundColor: "color-mix(in srgb, var(--accent) 8%, transparent)",
      },
      ".cm-gutters": {
        backgroundColor: "var(--bg)",
        color: "var(--muted)",
        borderRight: "1px solid var(--border)",
      },
      ".cm-activeLineGutter": {
        backgroundColor: "color-mix(in srgb, var(--accent) 12%, transparent)",
        color: "var(--text)",
      },
      ".cm-foldPlaceholder": {
        backgroundColor: "color-mix(in srgb, var(--muted) 20%, transparent)",
        color: "var(--text)",
        border: "1px solid var(--border)",
      },
      ".cm-matchingBracket, .cm-nonmatchingBracket": {
        backgroundColor: "color-mix(in srgb, var(--accent) 25%, transparent)",
        outline: "none",
      },
      ".cm-tooltip": {
        backgroundColor: "var(--surface)",
        color: "var(--text)",
        border: "1px solid var(--border)",
      },
      ".cm-panels": {
        backgroundColor: "var(--surface)",
        color: "var(--text)",
        borderTop: "1px solid var(--border)",
      },
      ".cm-panels input, .cm-panels button": {
        backgroundColor: "var(--bg)",
        color: "var(--text)",
        border: "1px solid var(--border)",
      },
      ".cm-searchMatch": {
        backgroundColor: "color-mix(in srgb, var(--ok) 30%, transparent)",
      },
      ".cm-searchMatch.cm-searchMatch-selected": {
        backgroundColor: "color-mix(in srgb, var(--accent) 40%, transparent)",
      },
    },
    { dark },
  );
}

export function JsonEditor({
  value,
  onChange,
  id,
  ariaLabel,
  className = "json-editor",
  minHeight = 320,
  fillViewport = false,
  schema,
  externalDiagnostic,
}: JsonEditorProps) {
  const [scheme, setScheme] = useState<Scheme>(preferredScheme);

  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return;
    const mql = window.matchMedia("(prefers-color-scheme: light)");
    const listener = (event: MediaQueryListEvent) => setScheme(event.matches ? "light" : "dark");
    mql.addEventListener("change", listener);
    return () => mql.removeEventListener("change", listener);
  }, []);

  const theme = useMemo(() => buildUiTheme(scheme === "dark"), [scheme]);

  const externalLinter = useMemo(() => {
    if (!externalDiagnostic) return null;
    const { path, message } = externalDiagnostic;
    return linter((view) => {
      const doc = view.state.doc.toString();
      const range = path ? resolveJsonPathRange(doc, path) : null;
      const diagnostic: Diagnostic = range
        ? { from: range.from, to: range.to, severity: "error", message }
        : {
            from: 0,
            to: Math.max(doc.length, 1),
            severity: "error",
            message,
          };
      return [diagnostic];
    });
  }, [externalDiagnostic]);

  const extensions = useMemo(() => {
    const base = [
      json(),
      linter(jsonParseLinter(), { delay: 500 }),
      lintGutter(),
      syntaxHighlighting(jsonHighlight),
    ];
    const withSchema = !schema
      ? base
      : // Enable schema-driven linting, hovers and completion while still keeping the plain
        // JSON parse linter so raw-syntax mistakes surface immediately even if the schema hasn't
        // loaded yet or the schema linter needs a debounce.
        [
          ...base,
          linter(jsonSchemaLinter(), { needsRefresh: handleRefresh, delay: 500 }),
          jsonLanguage.data.of({ autocomplete: jsonCompletion() }),
          hoverTooltip(jsonSchemaHover()),
          stateExtensions(schema),
        ];
    return externalLinter ? [...withSchema, externalLinter] : withSchema;
  }, [schema, externalLinter]);

  return (
    <div id={id} className={fillViewport ? `${className} json-editor--fill` : className}>
      <CodeMirror
        value={value}
        onChange={onChange}
        height={fillViewport ? "100%" : `${minHeight}px`}
        minHeight={fillViewport ? `${minHeight}px` : undefined}
        extensions={extensions}
        theme={theme}
        aria-label={ariaLabel}
        basicSetup={{
          lineNumbers: true,
          foldGutter: true,
          highlightActiveLine: true,
          autocompletion: Boolean(schema),
        }}
      />
    </div>
  );
}
