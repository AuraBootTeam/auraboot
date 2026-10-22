# Platform application assembly

`platform-app` owns the executable full-AI application. Build it from `platform/`:

```sh
./gradlew :platform-app:bootJar :platform-app:test :platform-app:verifyApplicationAssembly
```

The output is `platform-app/build/libs/AuraBoot-<version>-boot.jar`. The legacy
`:bootJar` command copies the exact application bytes to `build/libs/` for existing
launchers and image builders. Both `:bootRun` and `:platform-app:bootRun` select the
same launcher and retain the established JVM settings.

This preparation slice preserves the full AI implementation in the root library.
It does not yet provide an AI-free assembly. `core-only` retains its existing
application-mode meaning and does not mean AI-free. Legacy scan configurations and
full-context tests remain in root until the implementation extraction.

The application selects infrastructure runtime providers. Core publication must
not depend on app, agent or infrastructure implementations. Explicit scheduler
opt-in retains the existing `-PwithSchedulerXxl` behavior; the default application
contains no XXL-JOB runtime.

Launcher-mode tests belong to app. Existing root full-context suites and opt-in
fixture artifacts keep their current selectors until their extraction is ready;
the backend workflow explicitly executes app tests and the artifact gate.
