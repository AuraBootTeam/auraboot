# Connector SDK

## TL;DR

The Connector SDK separates abstract `Connector*` types (registry, adapter contract, invocation context/result, descriptor)
from protocol-specific implementations. Each protocol ships its own entity, service, adapter, and controller — the registry
routes by `protocolType` at runtime. Today two protocols are available: `http` (REST APIs via `HttpConnectorAdapter`) and
`jdbc` (MySQL / PostgreSQL via `JdbcConnectorAdapter`). Future protocols planned: `csv`, `kafka`, `grpc`.

---

## Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                         ConnectorRegistry                            │
│  routes by protocolType ──────────────────────────────────────────── │
│       │                                  │                           │
│       ▼                                  ▼                           │
│  HttpConnectorAdapter            JdbcConnectorAdapter                │
│  (protocolType = "http")         (protocolType = "jdbc")             │
│       │                                  │                           │
│       ▼                                  ▼                           │
│  ApiConnectorService             JdbcConnectorService                │
│  (HTTP execution, headers,       (DataSource pool, SQL               │
│   auth, response mapping)         execution, schema query)           │
└──────────────────────────────────────────────────────────────────────┘

  ConnectorInvocationContext ──► ConnectorAdapter.invoke()
                                         │
                                         ▼
                              ConnectorInvocationResult
                               (.success(), .data(), .errorMessage())
```

---

## Calling a Connector from Your Code

Use `ConnectorRegistry` to invoke any registered adapter by `protocolType`:

```java
@RequiredArgsConstructor
class MyService {
    private final ConnectorRegistry registry;

    Map<String, Object> fetchProducts() {
        ConnectorInvocationContext ctx = new ConnectorInvocationContext(
            MetaContext.getCurrentTenantId(),
            "01HXXX...PID...",   // connector record ID (ULID)
            "query",             // endpoint name defined in the connector's descriptor
            Map.of("category", "electronics"),
            false);              // dryRun
        ConnectorInvocationResult result = registry.invoke("jdbc", ctx);
        if (!result.success()) throw new BusinessException(result.errorMessage());
        return (Map<String, Object>) result.data();
    }
}
```

Key points:
- `protocolType` (`"jdbc"`, `"http"`, …) selects the adapter; the registry throws `IllegalArgumentException` for unknown types.
- `connectorId` is the ULID primary key of the persisted connector record.
- `endpointName` must match one of the names declared in the adapter's `ConnectorDescriptor`.
- `ConnectorInvocationResult.success()` is `false` for expected failures (bad config, unknown endpoint); it never throws in those cases.

---

## Add a New Connector in 5 Steps

This tutorial walks through adding a hypothetical **CSV connector** (`protocolType = "csv"`).

### Step 1 — Create the Entity

Mirror `JdbcConnector.java`. Create a JPA entity for the protocol's own fields (file path, delimiter, encoding, etc.) and a child entity for endpoints.

```java
// platform/src/main/java/com/auraboot/framework/connector/csv/entity/CsvConnector.java
@Entity
@Table(name = "ab_csv_connector")
@EntityListeners(AuditingEntityListener.class)
public class CsvConnector extends TenantAwareEntity {

    @Id
    private String id;                  // ULID

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String filePath;            // path or URL to the CSV source

    private String delimiter = ",";

    private String encoding = "UTF-8";

    // ... getters / setters
}
```

Also add a DDL migration under `platform/src/main/resources/database/migrations/` (see **Migration / Rollback** section for the JDBC example).

### Step 2 — Create the Service

Mirror `JdbcConnectorServiceImpl`. Implement CRUD for the new entity plus an `executeQuery(CsvConnector, String, Map<String, Object>)` method for runtime invocation.

```java
// platform/src/main/java/com/auraboot/framework/connector/csv/service/impl/CsvConnectorServiceImpl.java
@Service
@RequiredArgsConstructor
@Transactional
public class CsvConnectorServiceImpl implements CsvConnectorService {

    private final CsvConnectorRepository repository;
    private final FieldEncryptionService encryptionService; // only if secrets exist

    @Override
    public CsvConnector create(String tenantId, CsvConnectorCreateRequest req) { ... }

    @Override
    public ConnectorInvocationResult execute(CsvConnector connector,
                                             String endpointName,
                                             Map<String, Object> params) {
        // parse CSV, apply filters from params, return rows
    }
}
```

### Step 3 — Create the Adapter

Extend `AbstractConnectorAdapter` and register as a Spring `@Component`. The `protocolType()` string must be lowercase, stable, and unique across all adapters.

```java
// platform/src/main/java/com/auraboot/framework/connector/csv/service/CsvConnectorAdapter.java
@Component
@RequiredArgsConstructor
public class CsvConnectorAdapter extends AbstractConnectorAdapter {

    private static final ConnectorDescriptor DESCRIPTOR = ConnectorDescriptor.builder()
        .protocolType("csv")
        .displayName("CSV File")
        .supportedEndpoints(List.of(
            EndpointDescriptor.of("query",  "Read rows from the CSV file"),
            EndpointDescriptor.of("schema", "Return column names and inferred types")
        ))
        .build();

    private final CsvConnectorService csvConnectorService;
    private final CsvConnectorRepository repository;

    @Override
    public String protocolType() {
        return "csv";
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;   // same instance every call — no new allocation
    }

    @Override
    public ConnectorInvocationResult invoke(ConnectorInvocationContext ctx) {
        CsvConnector connector = repository
            .findByIdAndTenantId(ctx.connectorId(), ctx.tenantId())
            .orElseThrow(() -> new IllegalArgumentException(
                "CSV connector not found: " + ctx.connectorId()));
        try {
            return csvConnectorService.execute(connector, ctx.endpointName(), ctx.params());
        } catch (IllegalArgumentException e) {
            return ConnectorInvocationResult.failure(e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("Unexpected error invoking CSV connector", e);
        }
    }

    @Override
    public boolean testConnection(ConnectorInvocationContext ctx) {
        try {
            CsvConnector connector = repository
                .findByIdAndTenantId(ctx.connectorId(), ctx.tenantId())
                .orElseThrow(() -> new IllegalArgumentException(
                    "CSV connector not found: " + ctx.connectorId()));
            // attempt to open/read header row only
            return csvConnectorService.canRead(connector);
        } catch (IllegalArgumentException e) {
            throw e;                      // re-throw: caller passed bad args
        } catch (Exception e) {
            return false;                 // transport / IO error → false, never throw
        }
    }
}
```

### Step 4 — Create the Controller

Mirror `JdbcConnectorController`. Mount under `/api/csv-connectors` and guard every mutating endpoint with `@RequirePermission(MetaPermission.SYS_CONNECTOR_MANAGE)`.

```java
// platform/src/main/java/com/auraboot/framework/connector/csv/controller/CsvConnectorController.java
@RestController
@RequestMapping("/api/csv-connectors")
@RequiredArgsConstructor
public class CsvConnectorController {

    private final CsvConnectorService csvConnectorService;

    @GetMapping
    @RequirePermission(MetaPermission.SYS_CONNECTOR_MANAGE)
    public List<CsvConnectorDTO> list() { ... }

    @PostMapping
    @RequirePermission(MetaPermission.SYS_CONNECTOR_MANAGE)
    public CsvConnectorDTO create(@RequestBody @Valid CsvConnectorCreateRequest req) { ... }

    @PutMapping("/{id}")
    @RequirePermission(MetaPermission.SYS_CONNECTOR_MANAGE)
    public CsvConnectorDTO update(@PathVariable String id,
                                  @RequestBody @Valid CsvConnectorUpdateRequest req) { ... }

    @DeleteMapping("/{id}")
    @RequirePermission(MetaPermission.SYS_CONNECTOR_MANAGE)
    public void delete(@PathVariable String id) { ... }

    @PostMapping("/{id}/test-connection")
    @RequirePermission(MetaPermission.SYS_CONNECTOR_MANAGE)
    public TestConnectionResponse testConnection(@PathVariable String id) { ... }
}
```

### Step 5 — Write Tests

**Unit test** (Mockito only — no Spring context needed) for the adapter:

```java
class CsvConnectorAdapterTest {

    private final CsvConnectorService service    = mock(CsvConnectorService.class);
    private final CsvConnectorRepository repo    = mock(CsvConnectorRepository.class);
    private final CsvConnectorAdapter adapter    = new CsvConnectorAdapter(service, repo);

    @Test
    void invoke_unknownEndpoint_returnsFailure() {
        CsvConnector connector = new CsvConnector();
        when(repo.findByIdAndTenantId("id1", "t1")).thenReturn(Optional.of(connector));
        when(service.execute(connector, "unknown", Map.of()))
            .thenThrow(new IllegalArgumentException("unknown endpoint"));

        ConnectorInvocationContext ctx =
            new ConnectorInvocationContext("t1", "id1", "unknown", Map.of(), false);
        ConnectorInvocationResult result = adapter.invoke(ctx);

        assertFalse(result.success());
        assertThat(result.errorMessage()).contains("unknown endpoint");
    }

    @Test
    void testConnection_ioError_returnsFalse() {
        CsvConnector connector = new CsvConnector();
        when(repo.findByIdAndTenantId("id1", "t1")).thenReturn(Optional.of(connector));
        when(service.canRead(connector)).thenThrow(new IOException("file not found"));

        ConnectorInvocationContext ctx =
            new ConnectorInvocationContext("t1", "id1", "query", Map.of(), false);
        assertFalse(adapter.testConnection(ctx));   // must NOT propagate IOException
    }
}
```

**Integration test** (in-memory CSV or temp file) for the service — mirror the pattern in
`platform/src/test/java/com/auraboot/framework/connector/jdbc/`.

---

## Contracts Your Adapter MUST Honor

| # | Contract | Notes |
|---|----------|-------|
| 1 | `protocolType()` is **lowercase, stable, unique** across all adapters | Changing it is a breaking change — existing connector records become unreachable |
| 2 | `descriptor()` returns the **same instance** every call | Build once as a `static final` constant; never allocate on each call |
| 3 | `invoke()` returns `ConnectorInvocationResult.failure(msg)` for **expected** errors | Validation failures, unknown endpoint names, missing config → failure result, never throw |
| 4 | `invoke()` **throws** only for unexpected runtime exceptions | Unexpected = infrastructure failure not caused by caller input |
| 5 | `testConnection()` MUST NOT throw on transport / IO errors → return `false` | Only re-throws `IllegalArgumentException` (caller passed a bad connector ID) |
| 6 | Tenant scoping via `MetaContext.getCurrentTenantId()` on **every** read/write | Never accept `tenantId` from the request body or path variable |
| 7 | Secrets (passwords, API keys) stored **encrypted** via `FieldEncryptionService` | Decrypt only in-memory at invocation time; never persist plaintext |
| 8 | Per-protocol pool/cache is a `@Component` with `@PreDestroy` cleanup | Evict the entry on connector update or delete to avoid stale connections |
| 9 | All mutating service methods annotated `@Transactional` | Includes create, update, delete; read-only methods may use `@Transactional(readOnly = true)` |
| 10 | Controller endpoints under `/api/<protocol>-connectors` | Pattern is consistent with existing `/api/http-connectors` and `/api/jdbc-connectors` |
| 11 | Every controller endpoint guarded by `@RequirePermission(MetaPermission.SYS_CONNECTOR_MANAGE)` | No unauthenticated or role-only access to connector management |
| 12 | DTOs use `jakarta.validation.constraints` annotations for early input validation | `@NotBlank`, `@NotNull`, `@Size`, etc. on request DTOs |

---

## Anti-Patterns

| # | Anti-Pattern | Why it's wrong / what to do instead |
|---|-------------|--------------------------------------|
| 1 | **Protocol-specific fields on the `Connector` record** (e.g., `jdbcUrl`, `filePath` on the shared entity) | The shared `Connector` record is protocol-agnostic. Keep protocol fields in the protocol's own entity (`JdbcConnector`, `CsvConnector`, etc.). The `Connector` record holds only `id`, `name`, `protocolType`, `tenantId`. |
| 2 | **Calling other adapters from inside an adapter** (fan-out) | Adapters are leaf nodes. Fan-out belongs in the orchestrating service or controller. The registry routes inbound calls; it is not a bus for adapter-to-adapter communication. |
| 3 | **Swallowing exceptions in `invoke()`** — returning `success(null)` for failures | Always return `ConnectorInvocationResult.failure(msg)` for expected errors. Returning `success(null)` silently corrupts callers that inspect `.data()`. Reserve `throw` for truly unexpected exceptions. |
| 4 | **Bypassing `MetaContext`** — accepting `tenantId` from the request body or a service parameter | Tenant identity must be resolved from the security context (`MetaContext.getCurrentTenantId()`), not trusted from caller input. Bypass enables cross-tenant data leaks. |
| 5 | **Exposing encrypted / raw password fields in API responses** | Use the `maskPassword()` helper (see `JdbcConnectorDTO`) before returning DTOs. Never serialize `password` or `secretKey` fields to the client. |

---

## Reference Implementations

| Component | File |
|-----------|------|
| `HttpConnectorAdapter` | `platform/src/main/java/com/auraboot/framework/connector/sdk/HttpConnectorAdapter.java` |
| `JdbcConnectorAdapter` | `platform/src/main/java/com/auraboot/framework/connector/jdbc/service/JdbcConnectorAdapter.java` |
| `JdbcConnectorService` / `JdbcConnectorServiceImpl` | `platform/src/main/java/com/auraboot/framework/connector/jdbc/service/impl/JdbcConnectorServiceImpl.java` |
| `JdbcConnectorController` | `platform/src/main/java/com/auraboot/framework/connector/jdbc/controller/JdbcConnectorController.java` |
| `JdbcDataSourcePool` (per-protocol pool example) | `platform/src/main/java/com/auraboot/framework/connector/jdbc/service/JdbcDataSourcePool.java` |
| DDL migration | `platform/src/main/resources/database/migrations/2026-05-09-jdbc-connector.sql` |
| JDBC connector tests | `platform/src/test/java/com/auraboot/framework/connector/jdbc/` |

---

## Migration / Rollback

The JDBC connector tables are added by the migration file shipped with Tasks 1–10.

```sql
-- Apply
psql -f platform/src/main/resources/database/migrations/2026-05-09-jdbc-connector.sql

-- Rollback (removes both JDBC connector tables; no data loss on other tables)
DROP TABLE IF EXISTS ab_jdbc_connector_endpoint;
DROP TABLE IF EXISTS ab_jdbc_connector;
```

For a new protocol (e.g., CSV), add a separate dated migration file following the same naming convention:

```sql
-- platform/src/main/resources/database/migrations/YYYY-MM-DD-csv-connector.sql
CREATE TABLE ab_csv_connector (
    id           VARCHAR(26)  PRIMARY KEY,
    tenant_id    VARCHAR(26)  NOT NULL,
    name         VARCHAR(255) NOT NULL,
    file_path    TEXT         NOT NULL,
    delimiter    VARCHAR(10)  NOT NULL DEFAULT ',',
    encoding     VARCHAR(32)  NOT NULL DEFAULT 'UTF-8',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Rollback
-- DROP TABLE IF EXISTS ab_csv_connector;
```
