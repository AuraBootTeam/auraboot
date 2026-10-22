# Platform Integration Tests (`src/integrationTest`)

This source set hosts **integration tests** for the OSS platform module — tests
that need a full Spring context and a real PostgreSQL database. It is separate
from `src/test` (unit tests) so unit tests stay fast and IT can be run on demand
or in a dedicated CI job.

Phase 2.1 of the bootstrap-unified plan
(`docs/plans/2026-05/bootstrap-unified.md`) introduced this scaffolding so
follow-up sub-PRs (`BootstrapRepairServiceIT`, `BootstrapStartupRunnerIT`,
`BootstrapAdminRepairControllerIT`) can land with real-DB integration coverage
instead of mocks.

## Layout

```
platform/
└── src/
    ├── test/                    # unit tests (existing) — runs via `./gradlew test`
    └── integrationTest/         # this source set
        └── java/
            ├── com/auraboot/framework/integration/
            │   └── IntegrationTestBase.java
            └── com/auraboot/.../<feature>IT.java
```

The `integration-test` Spring profile (`application-integration-test.yml`) is
inherited from `src/test/resources` — IT and unit tests share the same profile
config.

## Running

```bash
# Run all integration tests
cd platform && ./gradlew integrationTest

# Run one specific IT
./gradlew integrationTest --tests "BootstrapEngineServiceIT"

# Compile only (no execution) — useful for CI compile gate
./gradlew compileIntegrationTestJava
```

Integration tests are NOT part of the default `./gradlew test` run. They run on:

- Local dev: `./gradlew integrationTest` on demand
- CI: dedicated `integration-test` job in `.github/workflows/backend.yml`
- Pre-merge: `./gradlew check` triggers `compileIntegrationTestJava` (compile gate)

## Hard rules (per `auraboot-enterprise/docs/standards/core/testing-backend.md`)

1. **Real PostgreSQL only** — no H2, no in-memory DB, no mocks for the DB layer.
   Default: connect to host pg via `application-integration-test.yml`
   (`localhost:5432/aura_boot`). For isolated docker stacks, override via
   environment / `--spring.datasource.url=…` JVM arg.
2. **`@Transactional + @Rollback(true)`** — every IT auto-rolls back at the end
   of the test method, so production rows on a shared host DB are untouched.
3. **No mocks for SUT collaborators** — only mail/SMS/external IO are mocked.

## Writing a new IT

```java
package com.auraboot.framework.<feature>;

import com.auraboot.framework.integration.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

class MyServiceIT extends IntegrationTestBase {

    @Autowired private MyService svc;

    @Test
    void onFreshDb_executeProducesExpectedRow() {
        freshDb();                // truncate bootstrap tables in-transaction
        svc.execute(...);

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ab_system_config", Integer.class);
        assertThat(count).isPositive();
    }
}
```

Naming convention: file ends in `IT.java`. The IT runner picks up everything
under `src/integrationTest/java`.

### `freshDb()`

`IntegrationTestBase#freshDb()` runs `TRUNCATE … RESTART IDENTITY CASCADE`
across the canonical bootstrap tables:

- `ab_system_config`
- `ab_user_role`
- `ab_tenant_member`
- `ab_role`
- `ab_user`
- `ab_menu`
- `ab_tenant`
- `ab_plugin`

The CASCADE handles dependent FKs (e.g. `ab_invitation → ab_tenant`). The
truncate is rolled back at the end of the test, so the host DB is untouched at
commit time. Tables that don't exist on the connected stack are skipped with a
warning (forward-compat with smaller schemas).

### Cache eviction (Phase 2.2)

`SystemConfigServiceImpl` keeps an in-memory cache that survives `freshDb()`.
After `freshDb()`, ITs that read `system.*` config (or call `isInitialized()`)
must call:

```java
systemConfigService.evictCache();
```

See `BootstrapRepairServiceIT#resetDb()` for the canonical pattern. Phase 2.1
shipped without this and worked around the issue by asserting on the guard's
error message — Phase 2.2 added `evictCache()` to the public interface.

## Multi-worktree hygiene

Per `AGENTS.md` §11: when ≥2 git worktrees are active, IT runs that share
host pg / `~/.m2` must use an isolated docker stack. The default flow for
single-worktree work is host pg; the IT scaffolding does not currently
reconfigure the JDBC URL automatically — pass `-Dspring.datasource.url=…`
when running against an isolated stack.
