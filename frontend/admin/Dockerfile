FROM node:24-alpine AS build
RUN corepack enable && corepack prepare pnpm@11.1.3 --activate
WORKDIR /workspace
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY admin/package.json ./admin/package.json
RUN pnpm install --frozen-lockfile
COPY admin ./admin
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm --filter admin build

FROM node:24-alpine AS runtime
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000
WORKDIR /app
COPY --from=build --chown=node:node /workspace/admin/.next/standalone ./
COPY --from=build --chown=node:node /workspace/admin/.next/static ./admin/.next/static
USER node
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=12 \
  CMD node -e "fetch('http://127.0.0.1:3000/login').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "admin/server.js"]
