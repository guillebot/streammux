import { useEffect, useRef, useState, type CSSProperties } from "react";

type Props = {
  options: readonly string[];
  selected: string[];
  onChange: (next: string[]) => void;
  id?: string;
  ariaLabel?: string;
};

const wrapperStyle: CSSProperties = {
  position: "relative",
  minWidth: "12rem",
};

const triggerStyle: CSSProperties = {
  width: "100%",
  textAlign: "left",
  padding: "0.4rem 0.6rem",
  background: "var(--surface)",
  border: "1px solid var(--border)",
  borderRadius: 6,
  color: "inherit",
  cursor: "pointer",
  fontSize: "0.9rem",
};

const dropdownStyle: CSSProperties = {
  position: "absolute",
  top: "100%",
  left: 0,
  right: 0,
  zIndex: 20,
  marginTop: "0.25rem",
  maxHeight: "18rem",
  overflowY: "auto",
  background: "var(--surface)",
  border: "1px solid var(--border)",
  borderRadius: 6,
  boxShadow: "0 6px 18px rgba(0, 0, 0, 0.22)",
  padding: "0.35rem 0",
};

const itemStyle: CSSProperties = {
  display: "flex",
  alignItems: "center",
  gap: "0.4rem",
  padding: "0.3rem 0.65rem",
  cursor: "pointer",
  fontSize: "0.9rem",
};

const footerStyle: CSSProperties = {
  display: "flex",
  justifyContent: "space-between",
  gap: "0.5rem",
  padding: "0.35rem 0.65rem",
  borderTop: "1px solid var(--border)",
  fontSize: "0.85rem",
};

const linkButtonStyle: CSSProperties = {
  background: "none",
  border: "none",
  padding: 0,
  color: "var(--accent)",
  cursor: "pointer",
  font: "inherit",
};

function summarize(selected: string[], total: number): string {
  if (selected.length === 0) return "Any type";
  if (selected.length === total) return "All types";
  if (selected.length === 1) return selected[0];
  return `${selected.length} selected`;
}

export function EventTypeMultiSelect({ options, selected, onChange, id, ariaLabel }: Props) {
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const onDocMouseDown = (e: MouseEvent) => {
      const node = wrapperRef.current;
      if (node && !node.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDocMouseDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDocMouseDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const toggle = (opt: string) => {
    if (selected.includes(opt)) {
      onChange(selected.filter((s) => s !== opt));
    } else {
      onChange([...selected, opt]);
    }
  };

  return (
    <div ref={wrapperRef} style={wrapperStyle}>
      <button
        id={id}
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        style={triggerStyle}
        onClick={() => setOpen((o) => !o)}
      >
        {summarize(selected, options.length)}
        <span aria-hidden="true" style={{ float: "right", opacity: 0.6 }}>
          ▾
        </span>
      </button>
      {open ? (
        <div role="listbox" aria-multiselectable="true" style={dropdownStyle}>
          {options.map((opt) => {
            const checked = selected.includes(opt);
            return (
              <label key={opt} style={itemStyle}>
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggle(opt)}
                  aria-label={opt}
                />
                <span>{opt}</span>
              </label>
            );
          })}
          <div style={footerStyle}>
            <button
              type="button"
              style={linkButtonStyle}
              onClick={() => onChange([...options])}
            >
              Select all
            </button>
            <button
              type="button"
              style={linkButtonStyle}
              onClick={() => onChange([])}
              disabled={selected.length === 0}
            >
              Clear
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
