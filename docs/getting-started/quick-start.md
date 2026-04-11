# Quick Start (5 Minutes)

Get AuraBoot running locally with Docker Compose. By the end of this guide, you will have a fully functional AuraBoot instance with a PostgreSQL database, Spring Boot backend, and React frontend.

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (v24+) with Docker Compose v2
- [Git](https://git-scm.com/)
- At least 4 GB of free RAM

## Step 1: Clone the Repository

```bash
git clone https://github.com/AuraBootTeam/auraboot.git
cd auraboot
```

Expected output:

```
Cloning into 'auraboot'...
remote: Enumerating objects: ...
Receiving objects: 100% ...
```

## Step 2: Start the Services

```bash
docker compose --profile full up -d
```

This starts three containers:

| Container | Service | Port |
|---|---|---|
| `auraboot-postgres` | PostgreSQL 16 (with pgvector) | 5432 |
| `auraboot-backend` | Spring Boot API server | 6443 (internal) |
| `auraboot-frontend` | React app + BFF | 3000 |

Expected output:

```
[+] Running 4/4
 ✔ Network auraboot_default       Created
 ✔ Container auraboot-postgres    Healthy
 ✔ Container auraboot-backend     Healthy
 ✔ Container auraboot-frontend    Started
```

The backend takes about 60-90 seconds to start. Wait for the health check to pass:

```bash
docker compose ps
```

Expected output (wait until `backend` shows `healthy`):

```
NAME                 STATUS                   PORTS
auraboot-postgres    running (healthy)        0.0.0.0:5432->5432/tcp
auraboot-backend     running (healthy)        6443/tcp
auraboot-frontend    running                  0.0.0.0:3000->3000/tcp
```

## Step 3: Open the Browser

Navigate to [http://localhost:3000](http://localhost:3000).

You should see the AuraBoot login page.

## Step 4: Log In

Use the default admin credentials:

| Field | Value |
|---|---|
| Email | `admin@example.com` |
| Password | `ChangeMeOnFirstLogin!` |

After login, you land on the workspace dashboard.

## Step 5: Explore the Workspace

After logging in, you can:

- **Browse the sidebar menu** -- Navigate through the default modules
- **Open the Page Designer** -- Create and edit pages with drag-and-drop blocks
- **Try AuraBot** -- Click the AI assistant icon to ask questions in natural language
- **Check the API docs** -- Visit [http://localhost:6443/swagger-ui.html](http://localhost:6443/swagger-ui.html) for the interactive API reference

## What's Next

- [Build Your First App](first-app.md) -- Create a Task Tracker from scratch (30-minute tutorial)
- [Detailed Installation](installation.md) -- Build from source, configure environment variables, set up for production

## Troubleshooting

### Backend container stays "starting" for more than 3 minutes

Check the logs:

```bash
docker compose logs backend --tail 50
```

Common causes:
- **Port 5432 already in use** -- A local PostgreSQL instance is occupying the port. Stop it or change the port in `.env`.
- **Insufficient memory** -- Docker Desktop needs at least 4 GB RAM. Check Docker Desktop settings.

### "Connection refused" when opening http://localhost:3000

The frontend depends on the backend being healthy. Check that the backend is fully started:

```bash
docker compose logs backend | grep "Started MetaApplication"
```

If the backend is not started yet, wait and try again.

### Database schema errors in backend logs

If you see migration or schema errors, reset the database:

```bash
docker compose down -v
docker compose --profile full up -d
```

The `-v` flag removes the data volume, giving you a clean database.

### Port conflicts

If ports 3000, 5432, or 6443 are already in use, create a `.env` file in the project root:

```bash
# .env
AURABOOT_PORT=3001
POSTGRES_PORT=5433
```

Then restart:

```bash
docker compose down
docker compose --profile full up -d
```

### Container logs reference

```bash
# All containers
docker compose logs -f

# Specific container
docker compose logs backend -f
docker compose logs frontend -f
docker compose logs postgres -f
```

### Stopping everything

```bash
docker compose down          # Stop containers, keep data
docker compose down -v       # Stop containers and delete data volumes
```
