# Platform application assembly

`platform-app` owns the executable full-AI application. Build it from `platform/`:

```sh
./gradlew :platform-app:bootJar :platform-app:test :platform-app:verifyApplicationAssembly
```

The output is `platform-app/build/libs/AuraBoot-<version>-boot.jar`. The legacy
`:bootJar` command copies the exact application bytes to `build/libs/` for existing
launchers and image builders. Both `:bootRun` and `:platform-app:bootRun` select the
same launcher and retain the established JVM settings.

Application launchers, scan configurations and application YAML live in this module.
AI implementation and outbound MCP dependencies live in `platform-agent`; core is
a plain library. `core-only` retains its existing meaning of excluding CRM/BPM and
does not mean an AI-free product.

The application selects infrastructure runtime providers. Core publication must
not depend on app, agent or infrastructure implementations. Explicit scheduler
opt-in retains the existing `-PwithSchedulerXxl` behavior; the default application
contains no XXL-JOB runtime.

Launcher-mode and full-context tests belong to app. Run cross-module integration
selectors with `:platform-app:integrationTest`; independent core tests use `:test`
and hermetic agent tests use `:platform-agent:test`. Shared helpers use explicit
test-fixtures variants. Root `testApiJar`/`e2eFixtureSupportJar` remain opt-in and
are excluded from production runtime dependencies.
