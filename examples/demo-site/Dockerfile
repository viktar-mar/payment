FROM node:24.13.0-alpine3.23 AS build

WORKDIR /workspace

RUN corepack enable \
    && corepack prepare pnpm@11.1.3 --activate

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./frontend/
COPY frontend/sdk/package.json ./frontend/sdk/package.json
RUN cd frontend \
    && pnpm install --frozen-lockfile --filter @generic-payments/sdk...

COPY frontend/sdk ./frontend/sdk
RUN cd frontend \
    && pnpm --filter @generic-payments/sdk build

COPY examples/demo-site/package.json examples/demo-site/pnpm-lock.yaml ./examples/demo-site/
RUN cd examples/demo-site \
    && pnpm install --frozen-lockfile

COPY examples/demo-site/index.html examples/demo-site/payment-return.html examples/demo-site/tsconfig.json examples/demo-site/vite.config.ts ./examples/demo-site/
COPY examples/demo-site/src ./examples/demo-site/src
RUN cd examples/demo-site \
    && pnpm build

FROM nginxinc/nginx-unprivileged:1.29.5-alpine3.23

COPY --chown=101:101 examples/demo-site/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /workspace/examples/demo-site/dist /usr/share/nginx/html

EXPOSE 8080

USER 101:101
