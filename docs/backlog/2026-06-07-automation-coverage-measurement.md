---
type: backlog
status: active
created: 2026-06-07
---

# Automation backend coverage — GAP-C result & methodology (2026-06-07)

## Result

**Automation-package line coverage = 81.3% (2127/2616)** — exceeds the 80% target.

Measured as the **source-line union** of two runs (a line counts as covered if either
run covered it), because the gradle build and the docker backend build produce
different class IDs and cannot be `.exec`-merged directly:

1. **Gradle unit + IT suite** (`:test --tests 'com.auraboot.framework.automation.*'`)
   against the isolated stack's Postgres — 332/335 pass (see "Remaining" below).
2. **E2E golden suites** (Layer A designer-golden + Layer B automation-golden) run
   against the running backend with the JaCoCo agent attached.

### Per-package (union)

| Package | Line cov |
|---|---|
| util | 100.0% |
| scheduler | 99.1% |
| iot | 91.7% |
| typehandler | 88.8% |
| bpm | 88.5% |
| service/impl | 87.6% |
| listener | 87.3% |
| service | 86.4% |
| executor/impl | 83.3% |
| trigger/impl | 74.6% |
| controller | 43.8% |
| entity | 33.3% |
| event | 28.6% |
| **TOTAL** | **81.3%** |

The two weakest packages are low-risk: `entity` is mostly generated getters/setters,
and `controller`/`event` cover REST endpoints + the debug-event publisher that the
golden does not drive (the debug feature is exercised by `DebugSessionServiceImplTest`,
3 of whose pure-Mockito cases are red — see Remaining).

## Why the gradle-only number was 3.7% ("broken run")

The automation IT tests (`@SpringBootTest`, `@ActiveProfiles("integration-test")`)
need an external Postgres. The `integration-test` profile defaults to a **local dev DB**
(`jdbc:postgresql://localhost:5432/aura_boot`, user `ghj`, no password). With no such
DB, the Spring context fails to start → every IT errors → only pure unit tests ran
(~3.7%). Pointing the datasource at the isolated stack's PG fixes this:

```
SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/aura_boot?charSet=UTF8' \
SPRING_DATASOURCE_USERNAME=auraboot SPRING_DATASOURCE_PASSWORD=auraboot_dev \
SPRING_DATA_REDIS_HOST=localhost SPRING_DATA_REDIS_PORT=6479 \
./gradlew --no-daemon :test --continue --tests 'com.auraboot.framework.automation.*'
```

## Stale IT tests fixed (this session)

`AutomationServiceIntegrationTest` + `AutomationIntegrationTest` shared a `buildRequest`
helper that set `actions=[]` + `flowConfig={"type":"simple"}`, assuming "any non-empty
flowConfig bypasses the actions-required check". The validation was later **intentionally
tightened**: a flowConfig is "designer mode" only when `flowConfig.nodes` is a non-empty
list (`AutomationServiceImpl#validateCreateRequest` ~L469 — a degenerate flowConfig must
still pass the flat-field checks or `modelCode` stays null and the NOT-NULL insert 500s).
So the helper produced an invalid request → "At least one action is required" → 24 cases
cascaded red. Fix: the helper now supplies a valid flat `update_record` action; `enable()`
skips the SmartEngine deploy for flat-actions automations, so the full
create→enable→state→search→logs lifecycle works. Production code unchanged (it was correct).

## E2E-on-backend measurement recipe (reproducible)

1. Download the agent: `org.jacoco.agent-0.8.12-runtime.jar` → `/tmp/jacocoagent.jar`.
2. Recreate the backend with a transient compose override that adds to `JAVA_OPTS`:
   `-javaagent:/app/jacocoagent.jar=destfile=/tmp/jacoco-e2e.exec,output=file,dumponexit=true,includes=com.auraboot.framework.automation.*`
   (mount `/tmp/jacocoagent.jar:/app/jacocoagent.jar:ro`).
3. Run both golden suites (`automation-designer-golden.spec.ts` + `automation-golden.spec.ts`).
4. `docker stop` the backend (SIGTERM → `dumponexit` flush), `docker cp` the `.exec` out.
5. `java -jar jacococli.jar report jacoco-e2e.exec --classfiles <app.jar BOOT-INF automation classes> --sourcefiles platform/src/main/java --xml ...`.
6. Union the gradle `build/jacoco/test.exec` report with the E2E report at the source-line
   level (covered if either ci>0).

## Remaining (pre-existing, out of this gap's scope)

- **`DebugSessionServiceImplTest` — 3 red** (pure Mockito): the debug session service's
  `automationMapper.findByPid` stub isn't matched on 3 paths → "Automation not found:
  autom-1". Pre-existing (not a regression from the automation-golden work); the debug
  feature is otherwise covered. Track + fix separately.
- `controller`/`event` coverage could be raised by driving the AutomationController/
  WebhookController REST surface + debug-event publisher directly, if a higher target is set.
