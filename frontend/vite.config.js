import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
    // The shell's environment wins over .env files, so `VITE_API_PROXY=… npm run dev` always works.
    const env = { ...loadEnv(mode, process.cwd(), "VITE_"), ...process.env };
    // The Spring Boot API. In the Docker deployment Nginx serves the SPA and proxies the same paths.
    const apiTarget = env.VITE_API_PROXY || "http://localhost:8080";

    return {
        plugins: [react()],
        build: {
            rolldownOptions: {
                output: {
                    // React and the router change far less often than the app, so they get their own
                    // long-cached file and a deploy only re-downloads the app code.
                    codeSplitting: {
                        groups: [{ name: "vendor", test: /node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/, priority: 20 }]
                    }
                }
            }
        },
        // `vite preview` reuses this proxy as well.
        server: {
            port: Number(env.VITE_PORT) || 3000,
            proxy: {
                "/api": { target: apiTarget, changeOrigin: true },
                "/uploads": { target: apiTarget, changeOrigin: true },
                // Live chat: STOMP over a native WebSocket.
                "/ws": { target: apiTarget, changeOrigin: true, ws: true }
            }
        },
        test: {
            environment: "jsdom",
            globals: true,
            setupFiles: "./src/test/setup.js",
            css: false,
            // Screens may take up to 5 s to appear on a busy machine (see src/test/setup.js); a test that waits
            // for several of them needs more than the default 5 s in total.
            testTimeout: 15000
        }
    };
});
