import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

export const distributionLicenseFiles = [
  'LICENSE.txt', 'NOTICE', 'LICENSE-SCOPE.md', 'LICENSE-LEGACY-2.0.txt',
  ...['designer-sdk', 'dsl-runtime', 'dsl-types', 'nav-model', 'open-platform-sdk',
    'plugin-sdk', 'ui', 'web-testkit'].map((name) => `packages/${name}/LICENSE`),
  'platform/platform-mybatis-compat/NOTICE.auraboot',
  'platform/platform-mybatis-compat/licenses/ognl-3.4.17/LICENSE.txt',
];

export function copyDistributionLicenses(repoRoot, destination) {
  for (const name of distributionLicenseFiles) {
    const target = resolve(destination, name);
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(resolve(repoRoot, name), target);
  }
}

// An independent SDK must never inherit the platform's root license by fallback.
export function copyPackageLicense(sourceRoot, destination) {
  mkdirSync(destination, { recursive: true });
  copyFileSync(resolve(sourceRoot, 'LICENSE'), resolve(destination, 'LICENSE'));
}
