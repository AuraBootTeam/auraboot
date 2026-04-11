# Configuration Reference

AuraBoot is configured through environment variables and `application.yml` settings. Environment variables take precedence over file-based configuration.

## Environment Variables

### Required for Production

| Variable | Default | Description |
|----------|---------|-------------|
| `JWT_SECRET` | `dev-only-secret-...` | JWT signing secret. **Must be changed** in production. Generate with `openssl rand -hex 32` |
| `DATABASE_PASSWORD` | *(empty)* | PostgreSQL password |

### Database

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/aura_boot?charSet=UTF8` | JDBC connection URL |
| `DATABASE_USERNAME` | `auraboot` | PostgreSQL username |
| `DATABASE_PASSWORD` | *(empty)* | PostgreSQL password |
| `HIKARI_MAX_POOL_SIZE` | `20` | Maximum connection pool size |
| `HIKARI_MIN_IDLE` | `5` | Minimum idle connections |

### Security / JWT

| Variable | Default | Description |
|----------|---------|-------------|
| `JWT_SECRET` | `dev-only-secret-key-replace-in-production-min-32-chars` | HMAC-SHA256 signing key (min 32 characters) |
| `JWT_KID` | `key-1` | Key ID for JWT header. Used for key rotation |
| `JWT_EXPIRATION` | `86400` | Token expiration in seconds (default 24 hours) |
| `JWT_PREVIOUS_SECRET` | *(empty)* | Previous signing key for rolling rotation |
| `JWT_PREVIOUS_KID` | *(empty)* | Previous key ID |
| `FIELD_ENCRYPTION_KEY` | *(empty)* | AES key for encrypting sensitive fields (e.g., webhook secrets). Generate with `openssl rand -base64 32` |
| `FRONTEND_BASE_URL` | `http://localhost:5173` | Frontend URL for password reset emails and redirects |

**JWT Key Rotation:**

1. Generate a new key: `NEW_SECRET=$(openssl rand -hex 32)`
2. Set `JWT_PREVIOUS_SECRET=$JWT_SECRET` and `JWT_PREVIOUS_KID=$JWT_KID`
3. Set `JWT_SECRET=$NEW_SECRET` and `JWT_KID=key-$(date +%Y%m%d)`
4. Rolling restart backend -- new tokens use the new key, old tokens still verify
5. After one expiration period (24h), remove the `PREVIOUS` env vars

### Redis (Optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_HOST` | *(empty)* | Redis hostname. Set to enable Redis integration |
| `REDIS_PORT` | `6379` | Redis port |

When `REDIS_HOST` is not set, AuraBoot runs in single-instance mode with JVM-local locks and in-process event bus.

### AI / LLM Providers

| Variable | Default | Description |
|----------|---------|-------------|
| `ANTHROPIC_API_KEY` | *(empty)* | Anthropic API key for Claude models |
| `OPENAI_API_KEY` | *(empty)* | OpenAI API key |
| `GATEWAY_SECRET` | `your-secret-key-here` | AI gateway shared secret |

At least one LLM provider key is required to use AuraBot, ChatBI, and RAG features. Without any key, AI features are disabled but the platform functions normally.

### File Storage

| Variable | Default | Description |
|----------|---------|-------------|
| `AURA_FILE_STORAGE_PATH` | `./data/files` | Local filesystem path for file uploads |

For S3/MinIO/OSS, configure in `application.yml` under `aura.storage`:

```yaml
aura:
  storage:
    type: minio  # local | minio | oss | s3
    minio:
      endpoint: http://localhost:9000
      access-key: minioadmin
      secret-key: minioadmin
      bucket: aura-files
```

### Bootstrap

| Variable | Default | Description |
|----------|---------|-------------|
| `AURABOOT_BOOTSTRAP_ENABLED` | `false` | Auto-create default tenant and admin user on startup |
| `AURABOOT_BOOTSTRAP_MODE` | `none` | SaaS bootstrap mode: `setup`, `seed`, or `none` |

### Server

| Variable | Default | Description |
|----------|---------|-------------|
| `AURABOOT_PORT` | `3000` | Frontend exposed port (Docker) |
| `SERVER_PORT` | `6443` | Backend server port |

### Monitoring

| Variable | Default | Description |
|----------|---------|-------------|
| `ACTUATOR_ENDPOINTS` | `health,info,metrics,prometheus` | Exposed actuator endpoints |
| `ACTUATOR_ENV_ENABLED` | `false` | Enable `/actuator/env` (sensitive -- dev only) |
| `ACTUATOR_BEANS_ENABLED` | `false` | Enable `/actuator/beans` (sensitive -- dev only) |
| `ACTUATOR_CONFIGPROPS_ENABLED` | `false` | Enable `/actuator/configprops` (sensitive) |
| `ACTUATOR_LOGGERS_ENABLED` | `false` | Enable `/actuator/loggers` |

### MinIO (Docker)

| Variable | Default | Description |
|----------|---------|-------------|
| `MINIO_ROOT_USER` | `minioadmin` | MinIO admin username |
| `MINIO_ROOT_PASSWORD` | `minioadmin` | MinIO admin password |

### Grafana (Docker)

| Variable | Default | Description |
|----------|---------|-------------|
| `GF_ADMIN_USER` | `admin` | Grafana admin username |
| `GF_ADMIN_PASSWORD` | `admin` | Grafana admin password |

---

## application.yml Key Settings

### Password Policy

```yaml
security:
  password:
    min-length: 8
    max-length: 128
    require-uppercase: true
    require-lowercase: true
    require-digit: true
    require-special: false
    history-count: 5          # Prevent reuse of last N passwords
    expiry-days: 90           # Force password change after N days
    reset-token-expiry-minutes: 30
  lockout:
    max-attempts: 5           # Lock account after N failed logins
    duration-minutes: 30      # Lockout duration
```

### Performance Monitoring

```yaml
auraboot:
  performance:
    slow-query-threshold-ms: 500    # WARN log for queries > 500ms
    slow-query-log-params: true     # Include SQL text in log
    sql-count-warn-threshold: 10    # WARN when request has > 10 SQL queries
    sql-count-error-threshold: 50   # ERROR when request has > 50 SQL queries
    sql-count-header-enabled: true  # Add X-SQL-Count response header
```

### BPM Engine

```yaml
aura:
  bpm:
    engine: smartengine  # smartengine | camunda | flowable
```

### Event Bus Transport

```yaml
aura:
  event:
    transport: local     # local | redis | rabbitmq
```

For multi-instance deployments, use `redis` to broadcast events across instances.

### Message Queue

```yaml
aura:
  mq:
    type: memory         # memory | redis | kafka | rabbitmq
```

For production with background job processing, use `redis` (recommended -- zero extra infrastructure when Redis is already deployed).

### HTTP Client

```yaml
aura:
  http:
    connect-timeout: 5s
    read-timeout: 30s
    max-connections: 200
    max-per-route: 50
    retry:
      max-attempts: 3
      backoff: 1s
```

### Multi-Tenancy

```yaml
aura:
  multi-tenant:
    index-isolation: true        # Per-tenant database indexes
    constraint-isolation: true   # Per-tenant unique constraints
    context-validation:
      enabled: true              # Validate tenant context on every request
      fail-on-missing: true      # Reject requests without tenant context
```

### Snowflake ID Generation

```yaml
snowflake:
  worker-id: 1      # 0-31, unique per instance
  datacenter-id: 1  # 0-31, unique per datacenter
```

When running multiple backend instances, each instance needs a unique `worker-id` to prevent ID collisions.

### Agent Control Plane

```yaml
agent:
  enabled: true
  max-concurrent-runs: 5
  default-cost-limit: 1.0
  anthropic:
    api-key: ${ANTHROPIC_API_KEY:}
    base-url: https://api.anthropic.com
    default-model: claude-sonnet-4-6
    max-tokens: 4096
```

### OpenTelemetry Tracing

```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # 100% in dev; reduce to 0.1 in production
    propagation:
      type: w3c
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces
```

### Currency Conversion

```yaml
currency:
  ecb:
    enabled: false   # Enable scheduled ECB daily rate sync
    url: https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml
```

---

## Spring Profiles

| Profile | Purpose |
|---------|---------|
| `dev` | Development defaults (verbose logging, dev tools) |
| `local` | Local machine overrides (gitignored, for secrets) |
| `community` | Community edition feature set |
| `test` | Enables TestSeedController for E2E testing |
| `integration-test` | Test database configuration |

Set profiles via:

```bash
SPRING_PROFILES_ACTIVE=community,local
```

---

## Database Configuration

AuraBoot uses HikariCP for connection pooling:

```yaml
spring:
  datasource:
    url: ${DATABASE_URL}
    driver-class-name: org.postgresql.Driver
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 30000
```

### Connection Pool Sizing

| Deployment Size | `maximum-pool-size` | `minimum-idle` |
|-----------------|--------------------:|---------------:|
| Development | 10 | 2 |
| Small (< 50 users) | 20 | 5 |
| Medium (50-500 users) | 30 | 10 |
| Large (500+ users) | 50 | 15 |

Formula: `pool_size = cpu_cores * 2 + disk_spindles` (for a single instance).

---

## SMTP / Email Configuration

Email is used for password reset, notifications, and workflow task assignments. Configure in `application-local.yml`:

```yaml
spring:
  mail:
    host: smtp.example.com
    port: 587
    username: noreply@example.com
    password: <smtp-password>
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
      mail.smtp.starttls.required: true
```

---

## CORS Configuration

The frontend BFF handles CORS. For direct backend access, configure:

```yaml
CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
```

---

## Tomcat Tuning

```yaml
server:
  tomcat:
    max-connections: 8192
    accept-count: 100
    max-http-form-post-size: 104857600  # 100MB
    threads:
      max: 200
      min-spare: 10
  compression:
    enabled: true
    min-response-size: 1024
```

For high-traffic deployments, increase `max-connections` and `threads.max`. Monitor with the `/actuator/metrics` endpoint.
