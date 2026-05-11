# Detailed Installation

This guide covers all installation methods: Docker Compose for deployment and building from source for development.

## Docker Compose

### Minimal Setup (PostgreSQL only)

For local development where you run the backend and frontend manually:

```bash
docker compose up -d
```

This starts only PostgreSQL. You then run the backend and frontend from source (see [Build from Source](#build-from-source) below).

### Full Stack (Docker)

```bash
docker compose --profile full up --build -d
```

This builds and starts PostgreSQL + backend + frontend. The app is available at [http://localhost:3000](http://localhost:3000).

### With Optional Services

```bash
# Full stack + Redis (for multi-instance deployments)
docker compose --profile full --profile cache up --build -d

# Full stack + MinIO (S3-compatible file storage)
docker compose --profile full --profile storage up --build -d

# Full stack + Prometheus + Grafana (monitoring)
docker compose --profile full --profile monitoring up --build -d

# Everything
docker compose --profile full --profile cache --profile storage --profile monitoring up --build -d
```

### Docker Service Map

| Service | Profile | Port | Purpose |
|---|---|---|---|
| PostgreSQL 16 (pgvector) | *(always)* | 5432 | Primary database |
| Spring Boot backend | `full` | 6443 (internal) | API server |
| React frontend + BFF | `full` | 3000 | Web application |
| Redis 7 | `cache` | 6379 | Session cache, distributed locks |
| MinIO | `storage` | 9000 (API), 9001 (console) | S3-compatible file storage |
| Prometheus | `monitoring` | 9090 | Metrics collection |
| Grafana | `monitoring` | 3001 | Dashboards and visualization |

---

## Build from Source

### Prerequisites

| Requirement | Version | Check command |
|---|---|---|
| Java (JDK) | 21+ | `java -version` |
| Node.js | 20+ | `node -v` |
| pnpm | 9+ | `pnpm -v` |
| PostgreSQL | 15+ (16 recommended) | `psql --version` |
| Git | 2.30+ | `git --version` |

Redis is optional for single-instance development. The app runs without it using in-process locks.

### Step 1: Clone the Repository

```bash
git clone https://github.com/AuraBootTeam/auraboot.git
cd auraboot
```

### Step 2: Set Up the Database

Start PostgreSQL (via Docker or native install) and create the database:

```bash
# Option A: Use Docker for PostgreSQL only
docker compose up -d

# Option B: Native PostgreSQL -- create the database manually
createdb -U postgres aura_boot
psql -U postgres -d aura_boot -f platform/src/main/resources/database/schema.sql
```

### Step 3: Configure Environment

Create a local configuration file for development secrets (this file is gitignored):

```bash
cp platform/src/main/resources/application-local.yml.example \
   platform/src/main/resources/application-local.yml
```

Or set environment variables directly:

```bash
export DATABASE_URL="jdbc:postgresql://localhost:5432/aura_boot?charSet=UTF8"
export DATABASE_USERNAME="auraboot"
export DATABASE_PASSWORD="auraboot_dev"
export JWT_SECRET="$(openssl rand -base64 64)"
```

### Step 4: Start the Backend

```bash
cd platform
./gradlew bootRun
```

The backend starts on port **6443**. Wait for the log line:

```
Started MetaApplication in X seconds
```

### Step 5: Start the Frontend

In a new terminal:

```bash
cd web-admin
pnpm install
pnpm dev:full
```

The frontend starts on port **5173** (Vite dev server + BFF).

Use `pnpm dev:full` for foreground development. For background mode, run `pnpm sync-plugins` once and then launch `pnpm dev:web` plus `pnpm dev:bff` separately.

### Step 6: Open the Browser

Navigate to [http://localhost:5173](http://localhost:5173) and log in with:

| Field | Value |
|---|---|
| Email | `admin@auraboot.com` |
| Password | `Test2026x` |

---

## Environment Variables Reference

### Required

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/aura_boot?charSet=UTF8` | JDBC connection string |
| `DATABASE_USERNAME` | `auraboot` | Database username |
| `DATABASE_PASSWORD` | *(empty)* | Database password |
| `JWT_SECRET` | `dev-only-secret-key-...` | JWT signing secret (min 32 chars). **Must change in production.** Generate with `openssl rand -base64 64` |

### Application

| Variable | Default | Description |
|---|---|---|
| `AURABOOT_BOOTSTRAP_ENABLED` | `false` | Auto-create admin user and tenant on first start. Set `true` for Docker. |
| `FRONTEND_BASE_URL` | `http://localhost:5173` | Base URL for the frontend (used in emails and redirects) |
| `SPRING_PROFILES_ACTIVE` | `dev,local,test` | Active Spring profiles. Use `community` for Docker deployment. |

### Database Tuning

| Variable | Default | Description |
|---|---|---|
| `HIKARI_MAX_POOL_SIZE` | `20` | Maximum database connection pool size |
| `HIKARI_MIN_IDLE` | `5` | Minimum idle connections in pool |

### JWT / Security

| Variable | Default | Description |
|---|---|---|
| `JWT_SECRET` | *(dev key)* | Primary JWT signing key |
| `JWT_KID` | `key-1` | Key ID for JWT header |
| `JWT_EXPIRATION` | `86400` | Token expiration in seconds (default 24h) |
| `JWT_PREVIOUS_SECRET` | *(empty)* | Previous JWT key (for rotation) |
| `JWT_PREVIOUS_KID` | *(empty)* | Previous key ID (for rotation) |
| `FIELD_ENCRYPTION_KEY` | *(empty)* | AES key for encrypting sensitive fields. Generate with `openssl rand -base64 32` |

### Storage

| Variable | Default | Description |
|---|---|---|
| `AURA_FILE_STORAGE_PATH` | `./data/files` | Local file storage directory |
| `MINIO_ROOT_USER` | `minioadmin` | MinIO access key (when using MinIO profile) |
| `MINIO_ROOT_PASSWORD` | `minioadmin` | MinIO secret key |

### AI / LLM

| Variable | Default | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | *(empty)* | Anthropic API key for Claude models |
| `GATEWAY_SECRET` | *(empty)* | AI gateway authentication secret |

### Redis (Optional)

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |

### Monitoring

| Variable | Default | Description |
|---|---|---|
| `ACTUATOR_ENDPOINTS` | `health,info,metrics,prometheus` | Exposed actuator endpoints |
| `GF_ADMIN_USER` | `admin` | Grafana admin username |
| `GF_ADMIN_PASSWORD` | `admin` | Grafana admin password |

### Docker-specific

| Variable | Default | Description |
|---|---|---|
| `AURABOOT_PORT` | `3000` | Host port for the frontend container |
| `POSTGRES_DB` | `aura_boot` | PostgreSQL database name |
| `POSTGRES_USER` | `auraboot` | PostgreSQL username |
| `POSTGRES_PASSWORD` | `auraboot_dev` | PostgreSQL password |

---

## Health Check

After starting, verify the backend is healthy:

```bash
curl http://localhost:6443/actuator/health
```

Expected response:

```json
{
  "status": "UP"
}
```

For more detail (requires authentication):

```bash
curl http://localhost:6443/actuator/health \
  -H "Authorization: Bearer $JWT"
```

---

## Verification Checklist

After installation, verify each component:

- [ ] PostgreSQL is running: `docker compose ps` shows `postgres` as `healthy`
- [ ] Backend is running: `curl http://localhost:6443/actuator/health` returns `{"status":"UP"}`
- [ ] Frontend is accessible: Browser opens [http://localhost:5173](http://localhost:5173) (source) or [http://localhost:3000](http://localhost:3000) (Docker)
- [ ] Login works: `admin@auraboot.com` / `Test2026x`
- [ ] API docs load: [http://localhost:6443/swagger-ui.html](http://localhost:6443/swagger-ui.html)

## Running Tests

```bash
# Backend integration tests
cd platform
./gradlew test

# AI regression tests
./gradlew testAi

# Frontend E2E tests (requires backend + frontend running)
cd web-admin
pnpm install
NO_PROXY=localhost npx playwright test
```

---

## Next Steps

- [Quick Start](quick-start.md) -- If you just want to try AuraBoot with Docker
- [Build Your First App](first-app.md) -- Create a Task Tracker plugin from scratch
