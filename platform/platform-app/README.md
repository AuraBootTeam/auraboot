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

## Optional agent assembly

The public default remains the complete AI application. To build both variants:

```sh
./gradlew :platform-app:bootJar :platform-app:bootJarBase :platform-app:verifyBaseApplicationAssembly
```

`bootJarBase` produces `AuraBoot-<version>-base-boot.jar` without the agent module
and its exclusive MCP SDK dependencies. Select the composition with
`backend.agent: base` in the application manifest; omit it or use `with-agent`
for the full application. `backend.mode: core-only` is an independent product
boundary. Do not swap nested libraries or attach an agent jar to an already running
base process: deploy aligned module versions and restart the selected launcher so
assembly verification runs before traffic is accepted.

Authenticated clients discover the verified selection at
`GET /api/application/capabilities`. Schema v1 returns
`{"schemaVersion":1,"agent":{"state":"installed"}}` or `not_installed`.
Installation does not grant permissions, commercial entitlement or model access.
HTTP errors, unsupported contracts and network failures remain unknown.
Discovery has no persistent client cache and must be refreshed for a new session,
origin or reconnect. Legacy deployments without this API are unknown.

Plugins containing only AI navigation can declare `applicationModules: ["agent"]`
in `plugin.json`; the importer checks the entire manifest before resource writes.
The shared profile importer omits these optional entries only after authenticated
base discovery. Explicit requests to import them fail before any selected plugin
is written. Ordinary plugin imports remain independent of discovery. The default
full profiles retain every entry.

Switching an existing database to base retains agent definitions, conversation
members, messages and import history. AI routes/actions become unavailable; humans
can continue ordinary IM and workflows. Never delete AI tables, edit Flyway history,
or uninstall historical plugin resources to simulate a base assembly. To restore
AI, deploy the matching full artifact, restart and rediscover; the original provider,
authorization and risk-approval checks still apply.

For a local managed stack, use `scripts/oss-golden-stack.sh up <runtime>
--agent-mode base` (default: `with-agent`) with the same owned runtime, explicit
profile and normal source/artifact verification. A module-specific HTTP check or
health probe does not replace product/browser validation. No new public artifacts
or image tags are required to validate the variants privately.
