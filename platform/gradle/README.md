# Native library version selection

The normal platform/application build reads the repository's `VERSION`. It does
not read `LIBRARY_VERSION` and does not opt products into a dependency upgrade.

An explicit `-Paura.library.release=true` selects the committed `LIBRARY_VERSION`
for native Gradle project versions, including their Maven publications and
project dependency metadata. Missing, linked, dynamic or SNAPSHOT library versions
fail before tasks execute. An explicit value other than `true` is rejected; omit
the property for the normal application build.

`LIBRARY_VERSION` currently describes the platform library cohort. This is not a
requirement for independently owned libraries to share an application version.
Changing a version does not publish it, update consumers, or grant qualification.
The native producer must still freeze the source, validate its complete dependency
closure, run applicable tests and bind original artifact bytes. Remote publication
must enforce immutable coordinates and clean consumption; this version selector
does not supply or bypass those controls. Do not overwrite historical `1.0.0`
artifacts or rewrite their POMs to simulate a new native publication.

The isolated contract runner uses the actual Gradle script with real Java/Maven
plugins, compilation and a fixture-only file repository:

```bash
node scripts/dev/test-library-version.mjs \
  --gradle /absolute/path/to/the/admitted/gradle-sdk/bin/gradle \
  --evidence /absolute/fresh/managed-evidence-directory
```

It verifies both default and explicit versions, native POM/Gradle module edges,
and invalid-input guards. It runs offline with a private Gradle home and never
publishes to `~/.m2` or a remote repository. Fixture publication is not platform
application qualification or proof of a remote repository's behavior.

## Independent native library preparation

In the explicit library mode, `prepareLibraryCandidate` selects the root plus
the current build's projects from Gradle's resolved runtime graph. It publishes
only that scope through the native Maven tasks to an explicit fresh
`-Paura.library.output=/absolute/attempt-owned/output` outside the source project.
Unrelated providers are not implicitly published. Included builds require a
separate producer. There is no `bootJar`, image build or application assembly.

The create-once `library-publication-inventory.json` records original coordinates,
compiled JAR hashes, all native attached artifacts, POM/Module hashes and the
library version source. Every published artifact and metadata file must equal its
original Gradle output. Missing output, output reuse and metadata tampering fail.
This remains a candidate file repository, with `remotePublication=did_not_run` and
`releaseQualification=false`.

The managed Linux entry is `bash scripts/ci/prepare-library-candidate.sh`. It
requires the original frozen Core/Workspace context, checks source identity before
and after, copies the existing managed SmartEngine dependency capsule with hashes,
uses an isolated Maven input directory and invokes the original Workspace Gradle
runner. It does not invoke `publishToMavenLocal`, remotely publish, or alter other
products' selected dependencies.

## Extracted module compatibility

Normal builds preserve `auraPlatformVersion` when explicitly selected, otherwise
`VERSION`. Explicit library release mode uses `LIBRARY_VERSION`; combining the
preview override with library release mode fails before task execution.

The default candidate remains the Core runtime closure. For an application cohort,
select existing Gradle roots explicitly, for example:

```sh
./platform/gradlew -p platform -Paura.library.release=true \
  -Paura.library.roots=:platform-app,:platform-bom \
  -Paura.library.output=/absolute/fresh/isolated-repository prepareLibraryCandidate
```

The native runtime graph supplies JAR modules; the explicit platform root supplies
its original alignment POM and Gradle metadata. Schema 2 records the selected roots
and each publication's `jar|pom` type. BOMs have no compiled JAR; their metadata is
verified against original Gradle outputs. Schema 1 and the default Core-only entry
remain supported. This command neither builds an application image nor publishes
to a remote registry. Never reuse an immutable version for changed library bytes.
