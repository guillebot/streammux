import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const apiTarget = process.env.VITE_DEV_API_PROXY ?? "http://127.0.0.1:8080";
const catalogTarget = process.env.VITE_DEV_CATALOG_PROXY ?? "http://127.0.0.1:3000";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/jobs": {
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
