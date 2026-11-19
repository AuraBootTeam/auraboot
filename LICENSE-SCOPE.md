# AuraBoot 1.0 license scope

This scope accompanies the exact AuraBoot 1.0 distribution containing it.
The root [Community License 3.0](LICENSE.txt) applies to AuraBoot-owned
platform, official UI, documentation and build output unless a more specific
license or preserved third-party notice applies. It is source-available,
not OSI open source. No new permission is asserted over third-party code.

## Independent package licenses (preserved)

- [packages/designer-sdk/LICENSE](packages/designer-sdk/LICENSE) governs that package.
- [packages/dsl-runtime/LICENSE](packages/dsl-runtime/LICENSE) governs that package.
- [packages/dsl-types/LICENSE](packages/dsl-types/LICENSE) governs that package.
- [packages/nav-model/LICENSE](packages/nav-model/LICENSE) governs that package.
- [packages/open-platform-sdk/LICENSE](packages/open-platform-sdk/LICENSE) governs that package.
- [packages/plugin-sdk/LICENSE](packages/plugin-sdk/LICENSE) governs that package.
- [packages/ui/LICENSE](packages/ui/LICENSE) governs that package.
- [packages/web-testkit/LICENSE](packages/web-testkit/LICENSE) governs that package.

These packages keep their Apache-2.0 terms. A package manifest alone does not
relicense other platform/UI code. Substituting a package name does not place
the rest of the platform under the package license.

## Third-party components and inline notices

- Dependency JARs/npm packages/images retain their own LICENSE/NOTICE texts;
  [NOTICE](NOTICE) is a guide, not an exhaustive substitute for the exact
  distribution SBOM and embedded notices.
- [MyBatis compatibility carrier notices](platform/platform-mybatis-compat/NOTICE.auraboot)
  and [OGNL 3.4.17 license](platform/platform-mybatis-compat/licenses/ognl-3.4.17/LICENSE.txt)
  remain applicable to those components, including relocated bytecode.
- Files retaining explicit third-party copyright or license headers keep
  those terms; e.g. the Spring-derived token in
  `platform/src/main/java/com/auraboot/framework/auth/dto/EmailPasswordAuthenticationToken.java`.
- Dependencies and external assets not present in a particular package are
  not granted merely by being mentioned in documentation.

## Historical distributions and build artifacts

Published v1.0.0-rc.11 and other previously Apache-licensed copies keep their
grants; [the previous root text](LICENSE-LEGACY-2.0.txt) is preserved for
reference and attribution. It does not relicense new 1.0-only work.
An unchanged component legitimately acquired under Apache can still be used
under that grant; this distribution does not extinguish that option.

For a binary candidate, the release source SHA, build lock, SBOM, included
licenses, NOTICE and SHA256SUMS determine the actual included files. A
source-available root notice must not replace package-level or embedded
third-party notices. Official product downloads, orders and UI must identify
their exact version rather than suggesting that a mutable main branch is a
published release. A candidate is not a published stable release.
