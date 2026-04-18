import { defineConfig } from '@playwright/test';
import ossConfig from './playwright.oss.config';

/**
 * BPM regression Playwright config — OSS Epic E aggregated Spec 1 suite.
 *
 * Narrows the OSS scope down to specs tagged `@bpm-regression`. The base OSS
 * config cannot be filtered reliably from the CLI (`--grep` / positional file
 * args) because every project pins its own `testDir` + `testMatch`. To filter
 * by tag we have to set `grep` at the project level, which is what this file
 * does.
 *
 * Usage (preferred): `pnpm test:bpm-regression` or `bash scripts/oss-test.sh --bpm-regression`.
 */
const GREP = /@bpm-regression/;

export default defineConfig({
  ...ossConfig,
  // Filter out chromium-deep — its sole purpose (per playwright.config.ts) is to
  // isolate resource-intensive *-deep.spec.ts files with workers:1. The OSS
  // config wrapper overrides every project's testMatch with the OSS scope,
  // which inadvertently pulls all bpm specs into chromium-deep too, causing
  // each test to run twice and surfacing flakiness from second-pass UI state.
  // Our @bpm-regression specs aren't named *-deep.spec.ts and don't need the
  // resource-isolation treatment, so chromium alone is sufficient.
  projects: (ossConfig.projects ?? [])
    .filter((project) => project.name !== 'chromium-deep')
    .map((project) => {
      if (project.name === 'auth') {
        return project;
      }
      return { ...project, grep: GREP };
    }),
});
