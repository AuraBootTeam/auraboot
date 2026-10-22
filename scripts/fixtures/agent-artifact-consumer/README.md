# Published agent artifact consumer

This is an independent Gradle build. It deliberately has no included build,
project dependency, producer output directory or fallback AuraBoot repository.
The only `com.auraboot` repository is the explicit isolated publication directory.

From the workspace, invoke the managed wrapper with:

```sh
./aura gradle <runtime> --project <oss>/scripts/fixtures/agent-artifact-consumer \
  --wrapper <oss>/platform/gradlew -- --offline --no-daemon \
  -PpublicationRepository=<isolated-maven-repository> \
  -PpublicationVersion=<same-immutable-preview-version> \
  -PpublicationMetadata=module verifyPublishedArtifacts
```

Repeat with `-PpublicationMetadata=pom` to disable Gradle metadata redirection
and consume the actual Maven POMs. Publish aligned core, agent, app, plugin API
and app provider artifacts plus `auraboot-platform` before running.
Application configurations import that published BOM as an enforced platform;
Maven applications import it in dependencyManagement. The BOM derives its
complete transitive alignment from the approved app lock, not this test. `auraPlatformVersion` selects the
producer's explicit preview version; it does not change the default VERSION.
Never overwrite an existing version or use the global Maven repository.

The six executable modes check base, optional agent, full composition, core test
fixtures, agent test fixtures and explicitly requested test-API endpoints.
The Java probe checks actual class owners on each resolved classpath, and the
Gradle gate rejects producer project substitution or another AuraBoot version.
Only the full mode exposes application launchers; production modes must not
contain test fixtures. No database, external model or application service runs.

Maven classifier consumers request their main module explicitly; Gradle feature
consumers use `testFixtures` and must resolve the published fixture capability.
Both forms are intentional and tested independently.
