import { defineConfig } from '@playwright/test';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import baseConfig from './playwright.config';

const __dirname = dirname(fileURLToPath(import.meta.url));
const scopePath = resolve(__dirname, '../oss-scope.json');
const scope = JSON.parse(readFileSync(scopePath, 'utf-8')) as {
  test_paths: string[];
  test_excludes?: string[];
};

function escapeRegex(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function toScopedRegex(entry: string, specPattern = '.*\\.spec\\.ts'): RegExp {
  const trimmed = entry.replace(/\/\*\*$/, '');
  const escaped = escapeRegex(trimmed);
  const isFile = /\.ts$/.test(entry);
  return new RegExp(isFile ? `${escaped}$` : `${escaped}/(?:.*\\/)?${specPattern}$`);
}

function toScopedMatch(entries: string[], specPattern = '.*\\.spec\\.ts'): RegExp[] {
  return entries.map((entry) => toScopedRegex(entry, specPattern));
}

function keepDeepOnly(entries: string[]): string[] {
  return entries.filter((entry) => {
    if (/\.ts$/.test(entry)) {
      return /-deep\.spec\.ts$/.test(entry);
    }
    return true;
  });
}

const ossTestMatch = toScopedMatch(scope.test_paths);
const ossDeepTestMatch = toScopedMatch(keepDeepOnly(scope.test_paths), '.*-deep\\.spec\\.ts');
const ossTestIgnore = toScopedMatch(scope.test_excludes ?? []);

/**
 * OSS-scoped Playwright configuration.
 *
 * Extends the base config but restricts every project's testMatch to files
 * listed in the root `oss-scope.json` manifest. This prevents enterprise-only
 * specs (crm/sales/finance/etc.) from running in the OSS regression suite.
 *
 * Important: preserve the base project's intent. In particular, `chromium-deep`
 * must stay limited to `*-deep.spec.ts`; otherwise the OSS scope inflates that
 * project back to the whole suite, and a single main-project failure causes a
 * huge wave of misleading `did not run` counts when the dependent deep project
 * is skipped.
 *
 * Usage: npx playwright test -c playwright.oss.config.ts
 */
export default defineConfig({
  ...baseConfig,
  projects: (baseConfig.projects ?? []).map((project) => {
    // setup + auth projects keep their own testMatch — they're not
    // limited to the OSS test_paths scope.
    if (project.name === 'auth' || project.name === 'setup') {
      return project;
    }
    return {
      ...project,
      testMatch: project.name === 'chromium-deep' ? ossDeepTestMatch : ossTestMatch,
      testIgnore: [...(Array.isArray(project.testIgnore) ? project.testIgnore : project.testIgnore ? [project.testIgnore] : []), ...ossTestIgnore],
    };
  }),
});
