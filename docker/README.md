# AuraBoot — Docker Quick Start

## Prerequisites

- Docker 24+ with Docker Compose v2
- 4GB+ RAM available for Docker

## Quick Start

```bash
# Start the full platform (first run builds images, ~5-10 min)
docker compose --profile full up --build

# Open http://localhost:3000
# Login: admin@example.com / Test2026x
```

## Commands

```bash
# Start full stack (detached)
docker compose --profile full up -d

# View logs
docker compose --profile full logs -f

# Stop
docker compose --profile full down

# Reset everything (deletes all data!)
docker compose --profile full down -v
docker compose --profile full up --build
```

## Infrastructure Only

For local development, start only the databases:

```bash
docker compose up -d          # PostgreSQL + Redis + MinIO
docker compose down            # Stop infrastructure
```

Then run backend and frontend locally — see project README.

## Configuration

Copy `.env.example` to `.env` to customize:

```bash
cp .env.example .env
# Edit .env with your settings
```

Key settings:
- `AURABOOT_PORT` — Change the exposed port (default: 3000)
- `JWT_SECRET` — **Must change** for production
- `ANTHROPIC_API_KEY` — Enable AI features

## Monitoring (Prometheus + Grafana)

Start the monitoring stack alongside the full platform:

```bash
# Start full stack with monitoring
docker compose --profile full --profile monitoring up -d

# Or add monitoring to an existing running stack
docker compose --profile monitoring up -d
```

Access:
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3001 (admin / admin)

### Pre-built Dashboards

Grafana comes with 4 provisioned dashboards (auto-loaded on startup):

| Dashboard | Description |
|-----------|-------------|
| **AuraBoot Platform Overview** | API/permission/query rates, latency, cache hit rate, uptime |
| **API Performance** | Request rate, error rate, P50/P95/P99 latency, top slow endpoints |
| **Cache Performance** | Hit rate gauge, hits/misses counters, hit rate over time |
| **JVM Overview** | Heap/non-heap memory, GC pauses, threads, HikariCP connections |

### Prometheus Metrics

The backend exposes metrics at `/actuator/prometheus`. Key metric prefixes:

- `http_server_requests_*` — Spring Boot HTTP request metrics
- `meta_api_*` — Custom API request counters and timers
- `meta_cache_*` — Cache hit/miss counters
- `meta_permission_*` — Permission check counters and timers
- `meta_query_*` — Query execution counters and timers
- `meta_dsl_*` — DSL projection counters
- `jvm_*` — JVM memory, threads, GC
- `hikaricp_*` — Database connection pool

### Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `GF_ADMIN_USER` | `admin` | Grafana admin username |
| `GF_ADMIN_PASSWORD` | `admin` | Grafana admin password |

### Stop Monitoring

```bash
docker compose --profile monitoring down
```
