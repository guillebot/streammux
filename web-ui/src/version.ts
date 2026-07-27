/** Release version baked at build time from repo VERSION / VITE_APP_VERSION. */
export const appVersion = import.meta.env.VITE_APP_VERSION?.trim() || "dev";
