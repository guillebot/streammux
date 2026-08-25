import { useEffect, useRef, useState } from "react";

type Props = {
  options: readonly string[];
  selected: string[];
  onChange: (next: string[]) => void;
  id?: string;
  ariaLabel?: string;
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
    <div ref={wrapperRef} className="multiselect">
      <button
        id={id}
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        className={`multiselect__trigger${selected.length === 0 ? " is-empty" : ""}`}
        onClick={() => setOpen((o) => !o)}
      >
        <span className="multiselect__value">{summarize(selected, options.length)}</span>
        <span className="multiselect__caret" aria-hidden="true">
          ▾
        </span>
      </button>
      {open ? (
        <div className="multiselect__panel" role="listbox" aria-multiselectable="true">
          <ul className="multiselect__list">
            {options.map((opt) => {
              const isSelected = selected.includes(opt);
              return (
                <li key={opt}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={isSelected}
                    className={`multiselect__option${isSelected ? " is-selected" : ""}`}
                    onClick={() => toggle(opt)}
                  >
                    {opt}
                  </button>
                </li>
              );
            })}
          </ul>
          <div className="multiselect__footer">
            <button
              type="button"
              className="multiselect__linkbutton"
              onClick={() => onChange([...options])}
              disabled={selected.length === options.length}
            >
              Select all
            </button>
            <button
              type="button"
              className="multiselect__linkbutton"
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
