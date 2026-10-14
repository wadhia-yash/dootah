import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { copyFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath, URL } from "node:url";
// Static hosts without rewrites serve /console/ from its own copy of the app shell.
const consoleRoute: Plugin = {
  name: "dootah-console-route",
  apply: "build",
  writeBundle({ dir }) {
    if (!dir) return;
    mkdirSync(join(dir, "console"), { recursive: true });
    copyFileSync(join(dir, "index.html"), join(dir, "console/index.html"));
  },
};
export default defineConfig({
  base: process.env.DOOTAH_WEB_BASE || "/",
  plugins: [react(), tailwindcss(), consoleRoute],
  build: { assetsDir: "web-assets" },
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  server: {
    proxy: {
      "/v1": {
        target: process.env.DOOTAH_CLOUD_URL || "http://127.0.0.1:3100",
        changeOrigin: true,
      },
    },
  },
});
