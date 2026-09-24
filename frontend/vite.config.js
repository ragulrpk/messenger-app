import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import process from "node:process";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    watch: {
      usePolling: process.env.VITE_USE_POLLING === "true",
    },
    proxy: {
      "/api": {
        target: process.env.API_PROXY_TARGET || "http://127.0.0.1:8080",
        changeOrigin: true,
        // 2026-09-23: Tunnel authenticated live events without rewriting the browser Origin.
        ws: true,
        configure(proxy) {
          proxy.on("proxyReq", (proxyReq, request) => {
            // Vite is the local edge: overwrite, never append browser-supplied IPs.
            proxyReq.removeHeader("Forwarded");
            proxyReq.removeHeader("X-Forwarded-Host");
            proxyReq.setHeader("X-Forwarded-For", request.socket.remoteAddress);
            proxyReq.setHeader("X-Forwarded-Proto", "http");
          });
          proxy.on("proxyReqWs", (proxyReq, request) => {
            proxyReq.removeHeader("Forwarded");
            proxyReq.removeHeader("X-Forwarded-Host");
            proxyReq.setHeader("X-Forwarded-For", request.socket.remoteAddress);
            proxyReq.setHeader("X-Forwarded-Proto", "http");
          });
        },
      },
    },
  },
});
