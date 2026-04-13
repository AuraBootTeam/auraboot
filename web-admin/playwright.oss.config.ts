import { defineConfig, devices } from '@playwright/test';
import { readFileSync } from 'fs';
import { resolve, dirname, relative } from 'path';
import { fileURLToPath } from 'url';
import baseConfig from './playwright.config';

const __dirname = dirname(fileURLToPath(import.meta.url));
const scopePath = resolve(__dirname, '../oss-scope.json');
const scope = JSON.parse(readFileSync(scopePath, 'utf-8')) as {
  test_paths: string[];
  test_excludes?: string[];
};

function toRegex(entries: string[]): RegExp[] {
  return entries.map((entry) => {
    const trimmed = entry.replace(/\/\*\*$/, '');
    const escaped = trimmed.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const isFile = /\.ts$/.test(entry);
    const suffix = isFile ? '$' : '(?:/.*)?\\.spec\\.ts$';
    return new RegExp(escaped + suffix);
  });
}

const ossTestMatch = toRegex(scope.test_paths);
const ossTestIgnore = toRegex(scope.test_excludes ?? []);

/**
 * OSS-scoped Playwright configuration.
 *
 * Extends the base config but restricts every project's testMatch to files
 * listed in the root `oss-scope.json` manifest. This prevents enterprise-only
 * specs (crm/sales/finance/etc.) from running in the OSS regression suite.
 *
 * Usage: npx playwright test -c playwright.oss.config.ts
 */
export default defineConfig({
  ...baseConfig,
  projects: (baseConfig.projects ?? []).map((project) => {
    if (project.name === 'auth') {
      return project;
    }
    return {
      ...project,
      testMatch: ossTestMatch,
      testIgnore: [...(Array.isArray(project.testIgnore) ? project.testIgnore : project.testIgnore ? [project.testIgnore] : []), ...ossTestIgnore],
    };
  }),
});
