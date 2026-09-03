/** Release version baked at build time from CI / VITE_APP_VERSION (YYYYMMDD-NN). */
export const appVersion = import.meta.env.VITE_APP_VERSION?.trim() || "dev";
