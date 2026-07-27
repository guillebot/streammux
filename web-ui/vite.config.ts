import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

function readRepoVersion(): string | undefined {
  for (const rel of ["../VERSION", "VERSION"]) {
    try {
      const value = readFileSync(resolve(__dirname, rel), "utf8").trim();
      if (value) return value;
    } catch {
      // try next path (local dev vs Docker build context)
    }
  }
  return undefined;
}

if (!process.env.VITE_APP_VERSION) {
  process.env.VITE_APP_VERSION = readRepoVersion() ?? "dev";
}

const apiTarget = process.env.VITE_DEV_API_PROXY ?? "http://127.0.0.1:8080";
const catalogTarget = process.env.VITE_DEV_CATALOG_PROXY ?? "http://127.0.0.1:3000";
const repoRoot = resolve(__dirname, "..");

export default defineConfig({
  plugins: [react()],
  server: {
    fs: {
      allow: [repoRoot],
    },
    proxy: {
      "/jobs": {
        target: apiTarget,
        changeOrigin: true,
      },
      "/swagger-ui": {
        target: apiTarget,
        changeOrigin: true,
      },
      "/v3/api-docs": {
        target: apiTarget,
        changeOrigin: true,
      },
      "/catalog": {
        target: catalogTarget,
        changeOrigin: true,
      },
    },
  },
});
