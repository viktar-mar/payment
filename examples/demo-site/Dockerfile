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

COPY examples/demo-site/index.html examples/demo-site/payment-return.html examples/demo-site/form-load-diagnostic.html examples/demo-site/form-load-manual.html examples/demo-site/tsconfig.json examples/demo-site/vite.config.ts ./examples/demo-site/
COPY examples/demo-site/src ./examples/demo-site/src
# Pure browser diagnostic schema and its transitive runtime/type dependencies.
# Never copy the infrastructure directory (it also holds private local inputs).
COPY infrastructure/stripe-form-diagnostic-schema.mjs infrastructure/stripe-form-diagnostic-schema.d.mts \
    infrastructure/stripe-form-diagnostic-environment.mjs infrastructure/stripe-form-diagnostic-environment.d.mts \
    infrastructure/stripe-form-diagnostic-network.d.mts ./infrastructure/
ARG PAYMENT_DEMO_BUILD_MODE=production
RUN cd examples/demo-site \
    && pnpm build --mode "$PAYMENT_DEMO_BUILD_MODE"

FROM nginxinc/nginx-unprivileged:1.29.5-alpine3.23

COPY --chown=101:101 examples/demo-site/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /workspace/examples/demo-site/dist /usr/share/nginx/html

EXPOSE 8080

USER 101:101
