import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from "react";

type Props = {
  value: string;
  onChange: (next: string) => void;
  options: string[];
  disabled?: boolean;
  ariaLabel?: string;
  id?: string;
  placeholder?: string;
  invalid?: boolean;
};

const wrapperStyle: CSSProperties = {
  position: "relative",
  width: "100%",
  maxWidth: "32rem",
};

const inputStyle: CSSProperties = {
  paddingLeft: "2rem",
  paddingRight: "2rem",
};

const invalidInputStyle: CSSProperties = {
  ...inputStyle,
  borderColor: "var(--danger)",
  boxShadow: "0 0 0 1px var(--danger)",
};

const searchIconStyle: CSSProperties = {
  position: "absolute",
  top: "50%",
  left: "0.55rem",
  transform: "translateY(-50%)",
  width: "1rem",
  height: "1rem",
  color: "var(--muted)",
  pointerEvents: "none",
};

const clearButtonStyle: CSSProperties = {
  position: "absolute",
  top: "50%",
  right: "0.5rem",
  transform: "translateY(-50%)",
  width: "1.4rem",
  height: "1.4rem",
  padding: 0,
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  background: "transparent",
  color: "var(--muted)",
  border: "none",
  borderRadius: "50%",
  cursor: "pointer",
  fontSize: "1rem",
  lineHeight: 1,
};

const dropdownStyle: CSSProperties = {
  position: "absolute",
  top: "100%",
  left: 0,
  right: 0,
  zIndex: 20,
  marginTop: "0.25rem",
  maxHeight: "14rem",
  overflowY: "auto",
  background: "var(--surface)",
  border: "1px solid var(--border)",
  borderRadius: 6,
  boxShadow: "0 6px 18px rgba(0, 0, 0, 0.22)",
  padding: "0.25rem 0",
  listStyle: "none",
  margin: 0,
};

const itemBaseStyle: CSSProperties = {
  padding: "0.4rem 0.65rem",
  cursor: "pointer",
  fontSize: "0.92rem",
  whiteSpace: "nowrap",
  overflow: "hidden",
  textOverflow: "ellipsis",
};

const itemHighlightedStyle: CSSProperties = {
  ...itemBaseStyle,
  background: "color-mix(in srgb, var(--accent) 22%, transparent)",
};

const emptyStyle: CSSProperties = {
  padding: "0.4rem 0.65rem",
  color: "var(--muted)",
  fontStyle: "italic",
  fontSize: "0.9rem",
};

export function TopicCombobox({
  value,
  onChange,
  options,
  disabled,
  ariaLabel,
  id,
  placeholder,
  invalid,
}: Props) {
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(0);
  const [query, setQuery] = useState(value);
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLUListElement | null>(null);

  useEffect(() => {
    setQuery(value);
  }, [value]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return options;
    return options.filter((o) => o.toLowerCase().includes(q));
  }, [options, query]);

  useEffect(() => {
    if (highlight >= filtered.length) setHighlight(0);
  }, [filtered.length, highlight]);

  // Close the dropdown and drop any typed-but-uncommitted text.
  const cancel = () => {
    setOpen(false);
    setQuery(value);
  };

  useEffect(() => {
    if (!open) return;
    const onDocMouseDown = (e: MouseEvent) => {
      const node = wrapperRef.current;
      if (node && !node.contains(e.target as Node)) cancel();
    };
    document.addEventListener("mousedown", onDocMouseDown);
    return () => document.removeEventListener("mousedown", onDocMouseDown);
  }, [open, value]);

  useEffect(() => {
    if (!open || !listRef.current) return;
    const el = listRef.current.querySelector<HTMLLIElement>(`li[data-index="${highlight}"]`);
    if (el) el.scrollIntoView({ block: "nearest" });
  }, [highlight, open]);

  const commit = (v: string) => {
    onChange(v);
    setQuery(v);
    setOpen(false);
  };

  const clear = () => {
    onChange("");
    setQuery("");
    setOpen(true);
    setHighlight(0);
    inputRef.current?.focus();
  };

  const moveHighlight = (delta: 1 | -1) => {
    setOpen(true);
    setHighlight((h) => {
      const n = filtered.length;
      if (n === 0) return 0;
      return (h + delta + n) % n;
    });
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (disabled) return;
    switch (e.key) {
      case "ArrowDown":
        e.preventDefault();
        moveHighlight(1);
        break;
      case "ArrowUp":
        e.preventDefault();
        moveHighlight(-1);
        break;
      case "Enter":
        if (open && filtered[highlight]) {
          e.preventDefault();
          commit(filtered[highlight]);
        }
        break;
      case "Escape":
        if (open) {
          e.preventDefault();
          cancel();
        }
        break;
    }
  };

  const listboxId = id ? `${id}-listbox` : undefined;
  const showClear = !disabled && query.length > 0;

  return (
    <div ref={wrapperRef} style={wrapperStyle}>
      <svg
        aria-hidden="true"
        focusable="false"
        viewBox="0 0 24 24"
        style={searchIconStyle}
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <circle cx="11" cy="11" r="7" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
      </svg>
      <input
        ref={inputRef}
        id={id}
        type="text"
        role="combobox"
        aria-expanded={open}
        aria-autocomplete="list"
        aria-controls={listboxId}
        aria-activedescendant={
          open && filtered[highlight] ? `${listboxId}-opt-${highlight}` : undefined
        }
        aria-label={ariaLabel}
        aria-invalid={invalid || undefined}
        className="text-input"
        style={invalid ? invalidInputStyle : inputStyle}
        autoComplete="off"
        spellCheck={false}
        disabled={disabled}
        placeholder={placeholder}
        value={query}
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
          setHighlight(0);
        }}
        onFocus={() => setOpen(true)}
        onBlur={cancel}
        onKeyDown={onKeyDown}
      />
      {showClear ? (
        <button
          type="button"
          aria-label="Clear selection"
          title="Clear"
          style={clearButtonStyle}
          onMouseDown={(e) => e.preventDefault()}
          onClick={clear}
        >
          ×
        </button>
      ) : null}
      {open && !disabled ? (
        <ul
          ref={listRef}
          id={listboxId}
          role="listbox"
          style={dropdownStyle}
          onMouseDown={(e) => e.preventDefault()}
        >
          {filtered.length === 0 ? (
            <li style={emptyStyle} aria-disabled="true">
              No matching topics
            </li>
          ) : (
            filtered.map((opt, i) => (
              <li
                key={opt}
                id={listboxId ? `${listboxId}-opt-${i}` : undefined}
                data-index={i}
                role="option"
                aria-selected={i === highlight}
                style={i === highlight ? itemHighlightedStyle : itemBaseStyle}
                onMouseEnter={() => setHighlight(i)}
                onClick={() => commit(opt)}
              >
                {opt}
              </li>
            ))
          )}
        </ul>
      ) : null}
    </div>
  );
}
