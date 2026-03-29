# AuraBoot — Deployment Guide

## Quick Start (Docker)

The fastest way to run the full AuraBoot platform is with Docker Compose.
Only PostgreSQL is required — Redis and MinIO are optional.

```bash
# Clone the repository
git clone https://github.com/your-org/auraboot.git
cd auraboot

# Configure environment (optional — defaults work for evaluation)
cp .env.example .env
# Edit .env with your settings (at minimum, change JWT_SECRET for production)

# Start the full stack (first run builds images — allow 5-10 min)
docker compose --profile full up --build -d

# Open the app
open http://localhost:3000
# Default login: admin@example.com / Test2026x
```

## Infrastructure Only (Local Development)

For local development, start only PostgreSQL and run services locally:

```bash
# Start PostgreSQL only
docker compose up -d

# Run backend (port 6443)
cd platform && ./gradlew bootRun

# Run frontend (port 5173)
cd web-admin && npm run dev:full
```

## Profiles

| Profile | Command | What It Starts |
|---------|---------|----------------|
| Default | `docker compose up -d` | PostgreSQL |
| Full | `--profile full` | + Backend (6443), Frontend (3000) |
| Cache | `--profile cache` | + Redis (6379) — for multi-instance deployments |
| Storage | `--profile storage` | + MinIO (9000/9001) — for S3 file storage |
| Monitoring | `--profile monitoring` | + Prometheus (9090), Grafana (3001) |

Combine profiles as needed:

```bash
# Minimal (PostgreSQL + app)
docker compose --profile full up --build -d

# With Redis (multi-instance / distributed lock / real-time sync)
docker compose --profile full --profile cache up --build -d

# With MinIO (S3 file storage)
docker compose --profile full --profile storage up --build -d

# Everything
docker compose --profile full --profile cache --profile storage --profile monitoring up --build -d
```

### When to enable Redis

Redis is **not required** for single-instance deployments. Without Redis:
- Distributed locks use JVM-local locking
- Data sync SSE events are pushed in-process
- Event bus uses in-memory transport
- Message queue uses in-memory provider

Enable Redis (`--profile cache`) when:
- Running multiple backend instances behind a load balancer
- You need cross-instance real-time data sync
- You need distributed locking

To connect the backend to Redis, set `REDIS_HOST` in `.env`:
```bash
REDIS_HOST=redis
```

### When to enable MinIO

File storage defaults to local filesystem (`./data/files`). Enable MinIO (`--profile storage`) when:
- You need S3-compatible object storage
- Files need to survive container restarts without volume mounts
- You want a web UI for file management

## Environment Variables

Copy `.env.example` to `.env` and configure:

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `AURABOOT_PORT` | `3000` | No | Frontend exposed port |
| `POSTGRES_DB` | `aura_boot` | No | Database name |
| `POSTGRES_USER` | `auraboot` | No | Database user |
| `POSTGRES_PASSWORD` | `auraboot_dev` | **Yes (prod)** | Database password |
| `JWT_SECRET` | (weak default) | **Yes (prod)** | JWT signing secret (min 32 chars) |
| `REDIS_HOST` | (empty) | No | Set to `redis` to enable Redis |
| `ANTHROPIC_API_KEY` | — | No | Enable AI/AuraBot features |
| `OPENAI_API_KEY` | — | No | OpenAI provider (alternative LLM) |
| `MINIO_ROOT_USER` | `minioadmin` | No | MinIO admin username |
| `MINIO_ROOT_PASSWORD` | `minioadmin` | **Yes (prod)** | MinIO admin password |
| `GF_ADMIN_USER` | `admin` | No | Grafana admin username |
| `GF_ADMIN_PASSWORD` | `admin` | **Yes (prod)** | Grafana admin password |
| `AURABOOT_BOOTSTRAP_ENABLED` | `true` | No | Auto-create default tenant on startup |

Generate a strong JWT secret:
```bash
openssl rand -hex 32
```

## Health Check URLs

Once the stack is running, verify all services are healthy:

| Service | URL | Expected |
|---------|-----|---------|
| Frontend | http://localhost:3000 | Login page |
| Backend health | http://localhost:6443/actuator/health | `{"status":"UP"}` |
| Backend API | http://localhost:6443/api/meta/models | JSON response |
| MinIO console | http://localhost:9001 | MinIO web UI (if enabled) |
| Prometheus | http://localhost:9090 | Prometheus UI (if enabled) |
| Grafana | http://localhost:3001 | Grafana dashboards (if enabled) |

Quick health check via curl:
```bash
curl -s http://localhost:6443/actuator/health | jq .status
# Expected: "UP"
```

## Common Operations

```bash
# View logs (all services)
docker compose --profile full logs -f

# View logs (specific service)
docker compose logs -f backend
docker compose logs -f frontend

# Restart a service
docker compose restart backend

# Stop everything
docker compose --profile full down

# Reset all data (WARNING: deletes all volumes)
docker compose --profile full down -v
docker compose --profile full up --build -d

# Rebuild images after code changes
docker compose --profile full up --build -d backend
docker compose --profile full up --build -d frontend
```

## Production Checklist

Before going to production:

- [ ] Change `JWT_SECRET` to a cryptographically random value (`openssl rand -hex 32`)
- [ ] Change `POSTGRES_PASSWORD` to a strong password
- [ ] Set `REDIS_HOST=redis` and enable `--profile cache` for multi-instance
- [ ] Change `MINIO_ROOT_PASSWORD` if using MinIO
- [ ] Set `ANTHROPIC_API_KEY` if you want AI features
- [ ] Put a reverse proxy (nginx / Cloudflare) in front for TLS termination
- [ ] Configure SMTP settings for email notifications
- [ ] Set `GF_ADMIN_PASSWORD` if using Grafana monitoring
- [ ] Review `AURABOOT_BOOTSTRAP_ENABLED` — disable after initial setup if desired

## Reverse Proxy (nginx)

For production TLS termination, put nginx in front of the frontend container.
Example nginx config snippet:

```nginx
server {
    listen 443 ssl;
    server_name your-domain.com;

    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    location / {
        proxy_pass http://localhost:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## Troubleshooting

### Backend doesn't start

Check if PostgreSQL is healthy:
```bash
docker compose ps postgres
docker compose logs postgres
```

Wait for the backend health check — initial startup can take up to 2 minutes:
```bash
docker compose logs -f backend | grep -E "Started|ERROR|Exception"
```

### Frontend shows blank page / API errors

Check the BFF can reach the backend:
```bash
docker compose logs frontend
# Look for "SPRING_BOOT_URL" configuration and connection errors
```

Verify the backend is running:
```bash
curl -s http://localhost:6443/actuator/health
```

### Database connection refused

The backend connects to postgres via Docker network (`postgres:5432`).
If running backend locally against Docker postgres, use `localhost:5432`.

### Port conflicts

If ports 3000, 6443, or 5432 are in use, change `AURABOOT_PORT` in `.env`
or stop the conflicting processes.

### Rebuilding after code changes

```bash
# Rebuild and restart just the backend
docker compose --profile full up --build -d backend

# Rebuild and restart just the frontend
docker compose --profile full up --build -d frontend
```

### Reset and start fresh

```bash
docker compose --profile full down -v  # removes all data volumes
docker compose --profile full up --build -d
```
