# AuraBoot Frontend — Multi-stage Docker build
# Stage 1: Install dependencies + build React app
# Stage 2: Production runtime (SSR + BFF)
#
# Package manager: pnpm (workspace lockfile is pnpm-lock.yaml).
# If `pnpm install --frozen-lockfile` fails with ERR_PNPM_OUTDATED_LOCKFILE,
# refresh the lockfile on the host (`pnpm install` in repo root) and commit
# the result — the Docker build only consumes a deterministic lockfile.
#
# NOTE: This Dockerfile builds web-admin in isolation. The web-admin
# package.json depends on monorepo workspace packages (`@auraboot/core`,
# `@auraboot/dsl-types`, `@auraboot/nav-model`, `@auraboot/plugin-sdk`) that
# live outside web-admin/. Building from `web-admin/` as the context will
# fail with ERR_PNPM_WORKSPACE_PKG_NOT_FOUND for any package not under
# web-admin/packages/. Production deployments use the repo-root build flow
# (see scripts/build-web-admin.sh + ga-e2e docker-compose stack); this
# standalone Dockerfile is a starting point that requires a wider build
# context (`docker build -f web-admin/Dockerfile .` from the repo root)
# plus expanded COPY paths to actually succeed.

# ── Stage 1: Build ──────────────────────────────────────────
FROM node:18-alpine AS builder

WORKDIR /app

# Enable pnpm via corepack — workspace lockfile is pnpm-lock.yaml and the
# repo declares a `pnpm-workspace.yaml`. Using `npm ci` here would silently
# bypass the workspace links and install the wrong deps.
RUN corepack enable && corepack prepare pnpm@9 --activate

# Copy package files for dependency caching
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY packages ./packages

# Install all dependencies (including devDependencies for build)
RUN pnpm install --frozen-lockfile

# Copy source files needed for build
COPY app ./app
COPY public ./public
COPY vite.config.ts tsconfig.json react-router.config.ts tailwind.config.js ./
# Note: web-admin/plugins/ does not exist on disk — plugin frontends live
# under app/plugins/** (already copied via `COPY app ./app`). Historically
# this directory was conditional; keeping the COPY would break the build.

# Build the React application
RUN pnpm run build

# ── Stage 2: Runtime ────────────────────────────────────────
FROM node:18-alpine

WORKDIR /app

# pnpm runtime for production dependency install.
RUN corepack enable && corepack prepare pnpm@9 --activate

# Copy package files and install production dependencies only
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY packages ./packages
RUN pnpm install --frozen-lockfile --prod

# Copy built application from builder
COPY --from=builder /app/build ./build

# Copy BFF server source (runs via tsx at runtime)
COPY app/server ./app/server

# Install tsx for running TypeScript BFF server
RUN pnpm add tsx

# Copy tsconfig for path resolution
COPY tsconfig.json ./

# Create non-root user
RUN addgroup -g 1001 -S appuser && \
    adduser -S -u 1001 -G appuser appuser && \
    chown -R appuser:appuser /app
USER appuser

EXPOSE 3000

ENV NODE_ENV=production
ENV BFF_PORT=3000
ENV SPRING_BOOT_URL=http://backend:6443

# BFF serves as single entry point: /api/* proxy + static files + SPA fallback
CMD ["npx", "tsx", "app/server/bff.server.ts"]
