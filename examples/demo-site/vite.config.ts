import { defineConfig, type PreviewServer, type ViteDevServer, type UserConfig } from "vite";
import {fileURLToPath} from 'node:url';

function isPrivateReturnTarget(target: string | undefined): boolean {
  if (!target) return false;
  const pathname = target.split(/[?#]/, 1)[0]!;
  try {
    // Decode the path only for matching; never decode or retain a return query.
    const decoded = decodeURIComponent(pathname);
    return ['/payment-return','/payment-return.html','/subscription-return','/subscription-return.html'].includes(decoded);
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

export default defineConfig(({mode}):UserConfig=>({
  resolve: ['form-diagnostic','manual-payment'].includes(mode)?{alias:{'@generic-payments/sdk':fileURLToPath(new URL('../../frontend/sdk/src/index.ts',import.meta.url))}}:undefined,
  plugins: [{
    name: 'private-payment-return-request',
    configureServer: protectReturnRequests,
    configurePreviewServer: protectReturnRequests,
  }],
  build: { manifest:mode==='manual-payment',rollupOptions: { input: mode==='manual-payment'?{manualPayment:'manual-payment.html'}:mode==='form-diagnostic'?{formDiagnostic:'form-load-diagnostic.html',formManual:'form-load-manual.html'}:{main:'index.html',paymentReturn:'payment-return.html',subscriptionReturn:'subscription-return.html'} } },
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
}));
