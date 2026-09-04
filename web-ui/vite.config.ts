import { execSync } from "node:child_process";
import { resolve } from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

function resolveAppVersion(): string {
  const fromEnv = process.env.VITE_APP_VERSION?.trim();
  if (fromEnv) return fromEnv;

  for (const cwd of [resolve(__dirname, ".."), __dirname]) {
    try {
      const version = execSync("bash scripts/build-app-version.sh", {
        cwd,
        encoding: "utf8",
        stdio: ["ignore", "pipe", "ignore"],
      }).trim();
      if (version) return version;
    } catch {
      // try next path (repo root vs flat Docker /app layout)
    }
  }

  return "dev";
}

process.env.VITE_APP_VERSION = resolveAppVersion();

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
      "/api": {
        target: apiTarget,
        changeOrigin: true,
      },
      "/oauth2": {
        target: apiTarget,
        changeOrigin: true,
      },
      "/login/oauth2": {
        target: apiTarget,
        changeOrigin: true,
      },
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
      "/mcp-admin": {
        target: process.env.VITE_DEV_MCP_PROXY ?? "http://127.0.0.1:8090",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/mcp-admin/, "/admin"),
        configure: (proxy) => {
          const adminToken = process.env.MCP_ADMIN_TOKEN?.trim();
          if (!adminToken) {
            console.warn(
              "[vite] MCP_ADMIN_TOKEN is unset; /mcp-admin proxy will not authorize against MCP",
            );
          }
          proxy.on("proxyReq", (proxyReq) => {
            if (adminToken) {
              proxyReq.setHeader("X-Streammux-Mcp-Admin-Token", adminToken);
            }
          });
        },
      },
      "/health/mcp": {
        target: process.env.VITE_DEV_MCP_PROXY ?? "http://127.0.0.1:8090",
        changeOrigin: true,
        rewrite: () => "/healthz",
      },
    },
  },
});
