# platform-scheduler-xxl (optional XXL-JOB engine)

Optional scheduler engine that delegates triggering to an external
[XXL-JOB Admin](https://www.xuxueli.com/xxl-job/) while tasks keep executing
inside AuraBoot. Activates when `aura.scheduler.engine=xxl`; the default
engine remains the built-in database scheduler (`local`).

## Why this is a separate module

`xxl-job-core` is distributed under **GPL-3.0**. To keep the default AuraBoot
distribution GPL-free, this integration lives outside the default `bootJar`:

- `platform/build.gradle` has **no dependency** on this module.
- The default distribution never contains `com.xxl.job.*` classes.
- Setting `aura.scheduler.engine=xxl` without this module on the classpath
  fails fast at startup with a missing-bean error (no silent fallback).

## Enabling it in a self-built distribution

```bash
# Build the boot jar with the optional engine included
./gradlew -PwithSchedulerXxl=true --no-daemon :bootJar -x test
```

The `-PwithSchedulerXxl=true` flag adds this module (and therefore
`xxl-job-core`) to the boot jar as `runtimeOnly`. Without the flag the
default jar is unchanged.

Then configure the engine (`application.yml`):

```yaml
aura:
  scheduler:
    engine: xxl
    xxl:
      admin-addresses: http://xxl-job-admin:8080/xxl-job-admin
      # remaining keys: see XxlJobProperties
```

## License notice

Adding this module to a distribution makes the combined work subject to
GPL-3.0. Review the AuraBoot License FAQ (`LICENSE-FAQ.md`) before shipping
it commercially. AuraBoot itself remains under the AuraBoot License.
