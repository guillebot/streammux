import { useEffect, useMemo, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { json, jsonParseLinter } from "@codemirror/lang-json";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { linter, lintGutter } from "@codemirror/lint";
import { EditorView } from "@codemirror/view";
import { tags as t } from "@lezer/highlight";

export interface JsonEditorProps {
  value: string;
  onChange: (value: string) => void;
  id?: string;
  ariaLabel?: string;
  className?: string;
  minHeight?: number;
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

  return (
    <div id={id} className={className}>
      <CodeMirror
        value={value}
        onChange={onChange}
        height={`${minHeight}px`}
        extensions={[
          json(),
          linter(jsonParseLinter(), { delay: 500 }),
          lintGutter(),
          syntaxHighlighting(jsonHighlight),
        ]}
        theme={theme}
        aria-label={ariaLabel}
        basicSetup={{
          lineNumbers: true,
          foldGutter: true,
          highlightActiveLine: true,
          autocompletion: false,
        }}
      />
    </div>
  );
}
