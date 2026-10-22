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
