import { defineConfig, type PreviewServer, type ViteDevServer } from "vite";

function isPrivateReturnTarget(target: string | undefined): boolean {
  if (!target) return false;
  const pathname = target.split(/[?#]/, 1)[0]!;
  try {
    // Decode the path only for matching; never decode or retain a return query.
    const decoded = decodeURIComponent(pathname);
    return decoded === '/payment-return' || decoded === '/payment-return.html';
  } catch {
    // Malformed paths may themselves trigger Vite warnings. Drop their query too.
    return true;
  }
}

function protectReturnRequests(server: ViteDevServer | PreviewServer): void {
  // Connect logs originalUrl before .use middleware, so sanitize its entry.
  // This typed handle boundary is tied to pinned Vite/Connect: run the actual
  // privacy suite on upgrades. Node HTTP wire-debug dumps must stay disabled.
  const handle = server.middlewares.handle.bind(server.middlewares);
  server.middlewares.handle = (request, response, next) => {
    const original = request as typeof request & {originalUrl?: string};
    // An embedding parent can rewrite one field but preserve the other.
    if (isPrivateReturnTarget(request.url) || isPrivateReturnTarget(original.originalUrl)) {
      request.url = request.url?.split(/[?#]/, 1)[0];
      original.originalUrl = original.originalUrl?.split(/[?#]/, 1)[0];
    }
    return handle(request, response, next);
  };
}

export default defineConfig({
  plugins: [{
    name: 'private-payment-return-request',
    configureServer: protectReturnRequests,
    configurePreviewServer: protectReturnRequests,
  }],
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
