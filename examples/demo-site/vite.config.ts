import { defineConfig } from "vite";

export default defineConfig({
  build: { rollupOptions: { input: {main:'index.html',paymentReturn:'payment-return.html'} } },
  server: {
    host: "127.0.0.1",
    port: 4173,
    strictPort: true,
    headers: {'Referrer-Policy':'no-referrer'},
  },
  preview: {
    host: "127.0.0.1",
    port: 4173,
    strictPort: true,
  },
});
