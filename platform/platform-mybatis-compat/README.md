# Compatible MyBatis security carrier

This module produces `com.auraboot:mybatis-compat` at the selected platform
version. It is an AuraBoot vendor build, not an upstream MyBatis release.

## Composition

* MyBatis 3.5.19 implementation classes are retained byte-for-byte.
* Its embedded OGNL and Javassist classes are removed.
* OGNL 3.4.17 and Javassist 3.33.0-GA are relocated into
  `org.apache.ibatis.ognl` and `org.apache.ibatis.javassist` using Shadow 9.2.2.
* `upstream-inputs.json` locks input coordinates, SHA-256 digests and sourced
  upstream licenses. Input verification runs before relocation, manifest reading
  or extraction; a mismatch fails before any archive is opened. License paths
  are additionally confined to each input directory. Generated provenance, component
  metadata, original notices and dependency licenses travel inside the JAR.
* The standard JAR is the implementation artifact for Gradle and Maven consumers;
  intermediate relocation JARs are not published as runtime variants.

## Consumption

Use the Core API/BOM or depend explicitly on this carrier. Exclude
`org.mybatis:mybatis` from consumer graphs. Do not bundle framework classes in
plugins or rely on classpath order. Host startup and PF4J plugin loading reject
split or duplicate implementations through `MybatisCarrierIntegrity`.

The standard Core `:test` task depends on this module's complete security suite,
including when a caller selects only specific Core tests. Archive file and
directory modes are fixed to 0644/0755 rather than inherited from the build umask.

The module tests compare implementation bytes, validate metadata and uniqueness,
execute ordinary expressions and reproduce the advisory's reflection route with
an isolated classloader and a harmless temporary file. The old input creates the
file; the repaired input must reject invocation without creating it. Input-lock
mutation and reproducible rebuild checks are additional acceptance evidence.
The test task also exercises the actual Gradle build in isolated fixtures: an
unreviewed archive must fail before extraction, a digest-approved traversal entry
must still be rejected, and reviewed inputs must reproduce the qualified JAR.
These three build-security scenarios are separate from the JUnit test count.

The OGNL invocation denylist is not an untrusted-code sandbox. SQL/Mapper and
plugin authoring remain privileged. Real PostgreSQL, published-artifact consumers,
plugin lifecycle and unchanged release qualification protocols must pass before
a candidate is described as repaired. An empty standalone scanner result alone
is insufficient. Remove this vendor build when a compatible repaired upstream
carrier passes those same checks.
