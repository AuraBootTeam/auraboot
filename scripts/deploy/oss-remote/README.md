# OSS remote image deploy

One self-contained script that deploys the OSS platform to a remote host by
**building the images off-host and shipping them with `docker save | docker load`**.
Use it for hosts that can't pull the images from a registry (air-gapped, GHCR-blocked,
or CN networks). Nothing is compiled on the target host.

```
build (off-host) ─► save|ssh docker load ─► compose up ─► quickstart bootstrap ─► seed ─► verify
```

## What it does
- Backend image: builds the (arch-independent) Spring `bootJar` natively, then bakes it
  into an `eclipse-temurin:21-jre-alpine` runtime for the target arch — no slow QEMU Java compile.
- Frontend image: cross-builds for the target arch (`--platform`), injecting CN mirrors
  (`APK_MIRROR` / `NPM_REGISTRY`) without editing the canonical Dockerfile. ICP review
  settings are deliberately excluded from the build and are read by the BFF at runtime.
- Ships `docker-compose.remote.yml` + a mode override + `gateway.conf` + `schema.sql`
  (fresh-DB init) + `plugins/` + `quickstart.sh`.
- `quickstart.sh` creates the admin and imports the 11 core plugins.
- Seeds the showcase demo data (鑫然科技: accounts / leads / opportunities / workflows / AI+KB)
  from the build host over HTTP, using a minted admin `storageState`.
- Single-instance: Redis + MinIO are omitted (JVM-local locking, local-FS storage).

## Modes
- `MODE=direct` (default): the OSS `gateway` owns the host HTTP port (`PUBLIC_HTTP_PORT`, default 80).
  Put a TLS proxy in front for https.
- `MODE=coexist`: an existing reverse proxy on the host already owns 80/443. The OSS gateway
  joins that proxy's docker network (`EDGE_NETWORK`) with alias `gateway`, so its existing
  `reverse_proxy gateway:80` serves OSS with **no proxy-config change**. Pair with
  `STOP_CONTAINERS` to stop whatever app currently sits behind that proxy.

## Prereqs
- Build host: `docker buildx`, JDK 21 (repo gradle wrapper), and — if seeding —
  `pnpm -C web-admin install` (Playwright drives the seed).
- Target host: `docker` + `docker compose`, plus `python3` + `curl` (quickstart bootstrap).
- Passwordless `ssh $HOST`.

## Examples

Fresh host, http on :80, with demo data:
```bash
HOST=root@1.2.3.4 PUBLIC_URL=http://1.2.3.4 \
  scripts/deploy/oss-remote/deploy.sh
```

numnan.com — coexist behind the host's existing Caddy (CN mirrors), replacing whatever
app is behind it (this is exactly how the 2026-07-15 numnan test env was deployed):
```bash
HOST=root@110.42.255.233 PUBLIC_URL=https://numnan.com \
MODE=coexist EDGE_NETWORK=auraboot-bom-acceptance_default \
ICP_COMPLIANCE_ENABLED=1 \
STOP_CONTAINERS="auraboot-bom-acceptance-frontend-bff-1 auraboot-bom-acceptance-backend-1 \
  auraboot-bom-acceptance-gateway-1 auraboot-bom-acceptance-postgres-1 \
  auraboot-bom-acceptance-redis-1 auraboot-bom-acceptance-gerber-sidecar-1" \
APK_MIRROR=mirrors.aliyun.com NPM_REGISTRY=https://registry.npmmirror.com \
  scripts/deploy/oss-remote/deploy.sh
```

## Single phases
`STEP=build|ship|up|bootstrap|seed|verify ./deploy.sh` runs one phase; `SKIP_BUILD=1` /
`SKIP_SEED=1` skip the expensive ones. See the header of `deploy.sh` for all env vars.

## Toggle ICP review mode without rebuilding images

The frontend BFF reads these values at process startup:

```dotenv
ICP_COMPLIANCE_ENABLED=1
ICP_SITE_TITLE=个人技术
ICP_RECORD_NUMBER=浙ICP备2023054087号
```

After a host has received a frontend image containing runtime-config support, switch the
profile without building or uploading another image:

```bash
HOST=root@1.2.3.4 PUBLIC_URL=https://example.com \
ICP_COMPLIANCE_ENABLED=1 \
ICP_SITE_TITLE=个人技术 \
ICP_RECORD_NUMBER=浙ICP备2023054087号 \
STEP=config scripts/deploy/oss-remote/deploy.sh
```

`STEP=config` only updates the remote `.env` and force-recreates the frontend container
with its existing image. It does not restart the backend or change the database. Set
`ICP_COMPLIANCE_ENABLED=0` to turn the profile off. A plain `docker restart` is not
sufficient because Docker does not reload changed Compose environment values into an
existing container.

## Reverse a coexist deploy
```bash
cd /opt/auraboot-oss && docker compose -f docker-compose.remote.yml -f override.coexist.yml down   # keep -v off
docker start <the STOP_CONTAINERS>   # the existing proxy re-resolves `gateway` back to them
```
