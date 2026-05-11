# Docker Deployment

AuraBoot ships with a Docker Compose configuration that starts the full platform with a single command. Only PostgreSQL is required -- Redis, MinIO, and monitoring are optional profiles.

## Quick Start

```bash
git clone https://github.com/AuraBootTeam/auraboot.git
cd auraboot

# Configure environment (optional -- defaults work for evaluation)
cp .env.example .env

# Start the full stack
docker compose --profile full up --build -d

# Open the app
open http://localhost:3000
```

Default credentials: `admin@auraboot.com` / `Test2026x`

---

## Profiles

Docker Compose uses profiles to control which services start. Combine profiles as needed.

| Profile | Services | Ports |
|---------|----------|-------|
| *(default)* | PostgreSQL 16 (pgvector) | 5432 |
| `full` | + Backend (Spring Boot) + Frontend (BFF + SSR) | 6443, 3000 |
| `cache` | + Redis 7 | 6379 |
| `storage` | + MinIO (S3-compatible) | 9000, 9001 |
| `monitoring` | + Prometheus + Grafana | 9090, 3001 |

### Common Combinations

```bash
# Infrastructure only (for local development)
docker compose up -d

# Minimal production
docker compose --profile full up --build -d

# Production with Redis (multi-instance)
docker compose --profile full --profile cache up --build -d

# Production with file storage
docker compose --profile full --profile storage up --build -d

# Everything
docker compose --profile full --profile cache --profile storage --profile monitoring up --build -d
```

---

## Service Configuration

### PostgreSQL

| Setting | Value |
|---------|-------|
| Image | `pgvector/pgvector:pg16` |
| Port | 5432 |
| Extensions | pg_trgm, pgcrypto, pgvector |
| Data volume | `postgres_data` |
| Schema init | Auto-loaded from `platform/src/main/resources/database/schema.sql` |

Environment variables:

```bash
POSTGRES_DB=aura_boot        # Database name
POSTGRES_USER=auraboot       # Database user
POSTGRES_PASSWORD=auraboot_dev  # CHANGE IN PRODUCTION
```

Health check: `pg_isready` every 5 seconds, 10 retries.

### Backend (Spring Boot)

| Setting | Value |
|---------|-------|
| Port | 6443 (internal) |
| Depends on | PostgreSQL (healthy) |
| Profile | `community` |
| Data volume | `backend_data` (uploaded files, exports) |

Key environment variables:

```bash
DATABASE_URL=jdbc:postgresql://postgres:5432/aura_boot?charSet=UTF8
DATABASE_USERNAME=auraboot
DATABASE_PASSWORD=auraboot_dev
JWT_SECRET=<openssl rand -hex 32>
FRONTEND_BASE_URL=http://localhost:3000
SPRING_PROFILES_ACTIVE=community
AURABOOT_BOOTSTRAP_ENABLED=true
```

Health check: `wget http://localhost:6443/actuator/health` every 15 seconds, 120-second start period.

### Frontend (BFF + SSR)

| Setting | Value |
|---------|-------|
| Port | 3000 (exposed) |
| Depends on | Backend (healthy) |

Environment variables:

```bash
NODE_ENV=production
BFF_PORT=3000
SPRING_BOOT_URL=http://backend:6443
CORS_ALLOWED_ORIGINS=http://localhost:3000
```

### Redis (Optional)

| Setting | Value |
|---------|-------|
| Image | `redis:7-alpine` |
| Port | 6379 |
| Data volume | `redis_data` |

**When to enable Redis:**

- Running multiple backend instances behind a load balancer
- Cross-instance real-time data sync via SSE
- Distributed locking across instances
- Event bus cross-instance transport

Without Redis, AuraBoot uses JVM-local equivalents (in-memory locks, in-process event bus).

To connect backend to Redis, set:

```bash
REDIS_HOST=redis
```

### MinIO (Optional)

| Setting | Value |
|---------|-------|
| Image | `minio/minio:latest` |
| API Port | 9000 |
| Console Port | 9001 |
| Data volume | `minio_data` |

**When to enable MinIO:**

- S3-compatible object storage for uploads
- Files need to survive container restarts without host volume mounts
- Web UI for file management (http://localhost:9001)

Default credentials: `minioadmin` / `minioadmin` (change in production).

### Prometheus + Grafana (Optional)

| Service | Image | Port |
|---------|-------|------|
| Prometheus | `prom/prometheus:v2.51.0` | 9090 |
| Grafana | `grafana/grafana:11.0.0` | 3001 |

Prometheus scrapes the backend's `/actuator/prometheus` endpoint. Grafana ships with a pre-configured platform overview dashboard.

Default Grafana credentials: `admin` / `admin`.

---

## Volume Management

| Volume | Service | Purpose |
|--------|---------|---------|
| `postgres_data` | PostgreSQL | Database files |
| `redis_data` | Redis | Cache persistence |
| `minio_data` | MinIO | Object storage |
| `backend_data` | Backend | Uploaded files, exports, AI documents |
| `prometheus_data` | Prometheus | Metrics time-series data |
| `grafana_data` | Grafana | Dashboard configs and plugins |

### Backup

```bash
# PostgreSQL backup
docker compose exec postgres pg_dump -U auraboot aura_boot > backup_$(date +%Y%m%d).sql

# Restore
cat backup_20260411.sql | docker compose exec -T postgres psql -U auraboot aura_boot
```

### Reset All Data

```bash
docker compose --profile full down -v
docker compose --profile full up --build -d
```

---

## Logging

```bash
# All services
docker compose --profile full logs -f

# Specific service
docker compose logs -f backend
docker compose logs -f frontend
docker compose logs -f postgres

# Last 100 lines
docker compose logs --tail=100 backend
```

Backend logs are structured JSON in production. Key log patterns:

```bash
# Find errors
docker compose logs backend | grep ERROR

# Find slow queries
docker compose logs backend | grep "slow query"

# Find startup events
docker compose logs backend | grep "Started"
```

---

## Health Checks

| Service | URL | Expected |
|---------|-----|----------|
| Frontend | http://localhost:3000 | Login page |
| Backend | http://localhost:6443/actuator/health | `{"status":"UP"}` |
| PostgreSQL | `docker compose exec postgres pg_isready` | "accepting connections" |
| Redis | `docker compose exec redis redis-cli ping` | "PONG" |
| MinIO Console | http://localhost:9001 | MinIO web UI |
| Prometheus | http://localhost:9090 | Prometheus UI |
| Grafana | http://localhost:3001 | Grafana login |

```bash
# Quick backend health check
curl -s http://localhost:6443/actuator/health | jq .status
```

---

## Production Deployment

### SSL/TLS with Nginx

For production, place an nginx reverse proxy in front of the frontend:

```nginx
server {
    listen 443 ssl http2;
    server_name app.example.com;

    ssl_certificate     /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;

    # Frontend
    location / {
        proxy_pass http://localhost:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket support (for SSE / real-time sync)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # File upload size
    client_max_body_size 100M;
}

server {
    listen 80;
    server_name app.example.com;
    return 301 https://$host$request_uri;
}
```

### Production Checklist

- [ ] Set `JWT_SECRET` to a cryptographic random value (`openssl rand -hex 32`)
- [ ] Set strong `POSTGRES_PASSWORD`
- [ ] Set `MINIO_ROOT_PASSWORD` if using MinIO
- [ ] Set `GF_ADMIN_PASSWORD` if using Grafana
- [ ] Enable Redis (`--profile cache`) for multi-instance deployments
- [ ] Configure SMTP for email notifications
- [ ] Set `ANTHROPIC_API_KEY` and/or `OPENAI_API_KEY` for AI features
- [ ] Put a TLS-terminating reverse proxy in front
- [ ] Review `AURABOOT_BOOTSTRAP_ENABLED` -- disable after initial setup
- [ ] Set `AURABOOT_PORT` to your desired port if not 3000
- [ ] Configure backup schedule for PostgreSQL

### Scaling Considerations

The Docker Compose setup runs a single instance of each service. For high-availability:

1. **Database**: Use a managed PostgreSQL service (RDS, Cloud SQL, etc.) or PostgreSQL replication
2. **Redis**: Enable the `cache` profile and point multiple backend instances to the same Redis
3. **Backend**: Run multiple `backend` containers behind a load balancer. Enable Redis for distributed locks and cross-instance sync
4. **Frontend**: The BFF/SSR layer is stateless and can be scaled horizontally
5. **File Storage**: Switch from local storage to MinIO or S3 for shared file access

For Kubernetes-based scaling, see [Kubernetes Deployment](kubernetes.md).

---

## Common Operations

```bash
# Restart a single service
docker compose restart backend

# Rebuild after code changes
docker compose --profile full up --build -d backend
docker compose --profile full up --build -d frontend

# Stop everything
docker compose --profile full down

# View resource usage
docker compose --profile full top
docker stats
```

---

## Troubleshooting

### Backend does not start

```bash
# Check PostgreSQL is healthy
docker compose ps postgres
docker compose logs postgres

# Wait for backend startup (can take up to 2 minutes on first run)
docker compose logs -f backend | grep -E "Started|ERROR|Exception"
```

### Frontend shows blank page

```bash
# Check BFF can reach backend
docker compose logs frontend
# Look for SPRING_BOOT_URL configuration and connection errors

# Verify backend is running
curl -s http://localhost:6443/actuator/health
```

### Port conflicts

If ports 3000, 5432, or 6443 are in use:

```bash
# Change frontend port
AURABOOT_PORT=8080 docker compose --profile full up --build -d

# Or stop conflicting processes
lsof -i :3000
kill <pid>
```

### Database connection refused

- Docker containers connect via the Docker network (`postgres:5432`)
- Local development connects via `localhost:5432`
- Check `DATABASE_URL` matches your setup
