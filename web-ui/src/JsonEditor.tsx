import { useEffect, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { json, jsonParseLinter } from "@codemirror/lang-json";
import { linter, lintGutter } from "@codemirror/lint";
import { oneDark } from "@codemirror/theme-one-dark";

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

  return (
    <div id={id} className={className}>
      <CodeMirror
        value={value}
        onChange={onChange}
        height={`${minHeight}px`}
        extensions={[json(), linter(jsonParseLinter(), { delay: 500 }), lintGutter()]}
        theme={scheme === "dark" ? oneDark : undefined}
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
