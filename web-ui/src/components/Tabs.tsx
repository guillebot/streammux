import { createContext, useCallback, useContext, useMemo, useRef } from "react";
import type { KeyboardEvent, ReactNode } from "react";

export interface TabDescriptor<T extends string> {
  value: T;
  label: string;
  badge?: string | boolean;
  disabled?: boolean;
  disabledReason?: string;
}

export interface TabsProps<T extends string> {
  value: T;
  onChange: (next: T) => void;
  tabs: TabDescriptor<T>[];
  ariaLabel: string;
  children: ReactNode;
  className?: string;
  /** id prefix for tab/panel IDs so multiple Tabs on the same page don't collide. */
  idPrefix?: string;
}

interface TabsContextValue {
  activeValue: string;
  idPrefix: string;
}

const TabsContext = createContext<TabsContextValue | null>(null);

export function Tabs<T extends string>({
  value,
  onChange,
  tabs,
  ariaLabel,
  children,
  className,
  idPrefix = "tabs",
}: TabsProps<T>) {
  const buttonRefs = useRef<Map<T, HTMLButtonElement | null>>(new Map());

  const focusableIndex = useCallback(
    (index: number, direction: 1 | -1) => {
      // Skip over disabled tabs when navigating with arrow keys so the focus
      // ring always lands on something clickable.
      const total = tabs.length;
      for (let step = 1; step <= total; step++) {
        const candidate = tabs[(index + direction * step + total * step) % total];
        if (candidate && !candidate.disabled) return candidate.value;
      }
      return null;
    },
    [tabs],
  );

  const onKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      const currentIndex = tabs.findIndex((t) => t.value === value);
      if (currentIndex < 0) return;
      let next: T | null = null;
      switch (event.key) {
        case "ArrowRight":
        case "ArrowDown":
          next = focusableIndex(currentIndex, 1);
          break;
        case "ArrowLeft":
        case "ArrowUp":
          next = focusableIndex(currentIndex, -1);
          break;
        case "Home":
          next = tabs.find((t) => !t.disabled)?.value ?? null;
          break;
        case "End": {
          for (let i = tabs.length - 1; i >= 0; i--) {
            const t = tabs[i];
            if (t && !t.disabled) {
              next = t.value;
              break;
            }
          }
          break;
        }
        default:
          return;
      }
      if (next != null && next !== value) {
        event.preventDefault();
        onChange(next);
        buttonRefs.current.get(next)?.focus();
      }
    },
    [tabs, value, onChange, focusableIndex],
  );

  const ctxValue = useMemo<TabsContextValue>(
    () => ({ activeValue: value, idPrefix }),
    [value, idPrefix],
  );

  return (
    <div className={className ? `tabs ${className}` : "tabs"}>
      <div
        role="tablist"
        aria-label={ariaLabel}
        className="tab-list"
        onKeyDown={onKeyDown}
      >
        {tabs.map((t) => {
          const selected = t.value === value;
          return (
            <button
              key={t.value}
              ref={(el) => {
                buttonRefs.current.set(t.value, el);
              }}
              type="button"
              role="tab"
              id={`${idPrefix}-tab-${t.value}`}
              aria-selected={selected}
              aria-controls={`${idPrefix}-panel-${t.value}`}
              aria-disabled={t.disabled || undefined}
              title={t.disabled ? t.disabledReason : undefined}
              tabIndex={selected ? 0 : -1}
              className="tab-button"
              disabled={t.disabled}
              onClick={() => {
                if (!t.disabled) onChange(t.value);
              }}
            >
              <span className="tab-button-label">{t.label}</span>
              {t.badge ? (
                <span className="tab-badge" aria-hidden="true">
                  {typeof t.badge === "string" ? t.badge : "!"}
                </span>
              ) : null}
            </button>
          );
        })}
      </div>
      <TabsContext.Provider value={ctxValue}>{children}</TabsContext.Provider>
    </div>
  );
}

export interface TabPanelProps {
  value: string;
  children: ReactNode;
}

export function TabPanel({ value, children }: TabPanelProps) {
  const ctx = useContext(TabsContext);
  if (!ctx) {
    throw new Error("<TabPanel> must be rendered inside <Tabs>");
  }
  const { activeValue, idPrefix } = ctx;
  const active = value === activeValue;
  return (
    <div
      role="tabpanel"
      id={`${idPrefix}-panel-${value}`}
      aria-labelledby={`${idPrefix}-tab-${value}`}
      hidden={!active}
      className="tab-panel"
    >
      {active ? children : null}
    </div>
  );
}
