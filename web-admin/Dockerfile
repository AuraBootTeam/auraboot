# AuraBoot Frontend — Multi-stage Docker build
# Stage 1: Install dependencies + build React app
# Stage 2: Production runtime (SSR + BFF)

# ── Stage 1: Build ──────────────────────────────────────────
FROM node:18-alpine AS builder

WORKDIR /app

# Copy package files for dependency caching
COPY package.json package-lock.json ./

# Install all dependencies (including devDependencies for build)
RUN npm ci

# Copy source files needed for build
COPY app ./app
COPY public ./public
COPY vite.config.ts tsconfig.json react-router.config.ts tailwind.config.js ./
COPY plugins ./plugins

# Build the React application
RUN npm run build

# ── Stage 2: Runtime ────────────────────────────────────────
FROM node:18-alpine

WORKDIR /app

# Copy package files and install production dependencies only
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# Copy built application from builder
COPY --from=builder /app/build ./build

# Copy BFF server source (runs via tsx at runtime)
COPY app/server ./app/server

# Install tsx for running TypeScript BFF server
RUN npm install tsx

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
