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
  projects: (ossConfig.projects ?? []).map((project) => {
    if (project.name === 'auth') {
      return project;
    }
    return { ...project, grep: GREP };
  }),
});
