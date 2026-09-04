import { useEffect, useState } from "react";
import { getStoredTheme, setTheme, type ThemeMode } from "../lib/theme";

const CYCLE: ThemeMode[] = ["system", "light", "dark"];

function nextMode(current: ThemeMode): ThemeMode {
  const idx = CYCLE.indexOf(current);
  return CYCLE[(idx + 1) % CYCLE.length];
}

function label(mode: ThemeMode): string {
  if (mode === "light") return "Light";
  if (mode === "dark") return "Dark";
  return "System";
}

export function ThemeToggle() {
  const [mode, setMode] = useState<ThemeMode>(() => getStoredTheme());

  useEffect(() => {
    setTheme(mode);
  }, [mode]);

  return (
    <button
      type="button"
      className="shell-chrome-btn"
      title={`Theme: ${label(mode)} (click to change)`}
      aria-label={`Theme: ${label(mode)}`}
      onClick={() => setMode((m) => nextMode(m))}
    >
      {label(mode)}
    </button>
  );
}
