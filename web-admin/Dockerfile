# AuraBoot Frontend — Multi-stage Docker build
# Stage 1: Install dependencies + build React app (Vite + react-router)
# Stage 2: Production runtime (BFF + SSR)
#
# Build context: REPO ROOT (not web-admin/).
# The web-admin app depends on workspace packages that live OUTSIDE web-admin/:
#   - packages/dsl-types     → @auraboot/dsl-types
#   - packages/nav-model     → @auraboot/nav-model
#   - packages/plugin-sdk    → @auraboot/plugin-sdk
#   - web-admin/packages/core → @auraboot/core
# pnpm-workspace.yaml at the repo root is the single source of truth and the
# root pnpm-lock.yaml resolves all four workspace links plus runtime deps
# (ajv, etc.). Building from web-admin/ as the context cannot see the
# top-level packages/ tree, so we build from the repo root.
#
# Invocation:
#   docker build -f web-admin/Dockerfile -t auraboot-webadmin .
#
# docker-compose maps this via:
#   build:
#     context: .
#     dockerfile: web-admin/Dockerfile
#
# Lockfile drift: if `pnpm install --frozen-lockfile` errors with
# ERR_PNPM_OUTDATED_LOCKFILE, run `pnpm install --lockfile-only` at the repo
# root and commit the regenerated pnpm-lock.yaml.

# ── Stage 1: Build ──────────────────────────────────────────
FROM node:20-alpine AS builder

# Optional registry override — useful in environments where
# https://registry.npmjs.org is firewalled or rate-limited. Pass at build
# time, e.g. `docker build --build-arg NPM_REGISTRY=https://registry.npmmirror.com`.
ARG NPM_REGISTRY=https://registry.npmjs.org

# `npm_config_registry` is honored by both npm AND pnpm. Setting it as an
# env var (rather than via `pnpm config set`) means the value survives any
# subsequent USER switch and is inherited by all child npm/pnpm invocations.
ENV npm_config_registry=$NPM_REGISTRY

WORKDIR /app

# Bash is needed for scripts/sync-platform-plugins.sh (called by
# `pnpm --filter web-admin build` → `pnpm sync-plugins`).
RUN apk add --no-cache bash

# Install pnpm directly via npm. We avoid `corepack prepare` because it
# probes registry.npmjs.org regardless of the npm config registry, which
# fails on networks where only the mirror is reachable.
RUN npm install -g pnpm@9

# 1. Workspace manifests + lockfile (cache-friendly layer).
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./

# 2. Workspace package manifests so pnpm can resolve `workspace:*` links
#    without copying the full source yet (still cache-friendly).
COPY packages ./packages
COPY web-admin/package.json ./web-admin/package.json
COPY web-admin/packages ./web-admin/packages

# 3. Install everything (devDeps + workspace deps) using the repo-root
#    lockfile. --frozen-lockfile keeps builds reproducible. HUSKY=0
#    skips the git hook install (no .git/ inside the build context).
#    --ignore-scripts skips puppeteer's ~200MB chrome download which is
#    only needed by tests, not the build.
RUN HUSKY=0 pnpm install --frozen-lockfile --ignore-scripts

# 4. Source needed for the web-admin build. The build script chains
#    `pnpm sync-plugins` which reads plugins/platform/** + scripts/**.
COPY web-admin/app ./web-admin/app
COPY web-admin/public ./web-admin/public
COPY web-admin/vite.config.ts web-admin/tsconfig.json web-admin/react-router.config.ts web-admin/tailwind.config.js ./web-admin/
COPY plugins ./plugins
COPY scripts ./scripts

# 5. Build the React application. The repo's `pnpm build` script chains
#    `pnpm typecheck` which validates the entire workspace including
#    test files — the Docker build is a packaging step, not a CI gate, so
#    it skips typecheck (CI's frontend.yml runs `pnpm typecheck` separately
#    and is the source of truth for type correctness). Plugin route
#    generation still runs because Vite can't bundle without it.
WORKDIR /app/web-admin
RUN pnpm sync-plugins && pnpm exec react-router build
WORKDIR /app

# ── Stage 2: Runtime ────────────────────────────────────────
FROM node:20-alpine

ARG NPM_REGISTRY=https://registry.npmjs.org

# `npm_config_registry` is honored by both npm AND pnpm and survives a
# USER switch (unlike `pnpm config set` which writes per-user files).
ENV npm_config_registry=$NPM_REGISTRY

# Create non-root user up front so subsequent COPYs use --chown without
# triggering a massive recursive chown over the installed node_modules tree.
RUN addgroup -g 1001 -S appuser && \
    adduser -S -u 1001 -G appuser appuser && \
    mkdir -p /app && chown appuser:appuser /app

WORKDIR /app

RUN npm install -g pnpm@9

USER appuser

# 1. Workspace manifests + lockfile (so `pnpm install --prod` resolves the
#    same dependency graph used at build time).
COPY --chown=appuser:appuser package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY --chown=appuser:appuser packages ./packages
COPY --chown=appuser:appuser web-admin/package.json ./web-admin/package.json
COPY --chown=appuser:appuser web-admin/packages ./web-admin/packages

# 2. Install the web-admin workspace's dependency graph. We deliberately
#    do NOT pass `--prod` because the BFF entry point is a TypeScript file
#    executed via `tsx` (a devDep) at runtime, and removing devDeps would
#    also strip tsx + its tsconfig-discovery internals. HUSKY=0 disables
#    the `prepare` hook (no .git/ in the build context) and
#    --ignore-scripts skips puppeteer's ~200MB chrome download.
RUN HUSKY=0 pnpm install --frozen-lockfile --filter auraboot-app... --ignore-scripts

# 3. Built application + BFF runtime source. The BFF imports are not
#    confined to app/server/ — bff.server.ts (and its transitive deps)
#    pull in `~/utils/type`, `~/shared/services/session`,
#    `~/constants/AuthConstant`, and `~/plugins/core-designer/...types`,
#    so the entire app/ tree must be present for tsx to resolve the
#    `~/*` path alias at runtime.
COPY --chown=appuser:appuser --from=builder /app/web-admin/build ./web-admin/build
COPY --chown=appuser:appuser web-admin/app ./web-admin/app
COPY --chown=appuser:appuser web-admin/tsconfig.json ./web-admin/tsconfig.json

WORKDIR /app/web-admin

EXPOSE 3000

ENV NODE_ENV=production
ENV BFF_PORT=3000
ENV SPRING_BOOT_URL=http://backend:6443

# BFF serves as single entry point: /api/* proxy + static files + SPA fallback.
# Use the workspace-local tsx (node_modules/.bin/tsx) so it picks up the
# workspace tsconfig.json and resolves `~/*` path aliases correctly.
CMD ["./node_modules/.bin/tsx", "app/server/bff.server.ts"]
