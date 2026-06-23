import { defineConfig } from '@playwright/test';
import ossConfig from './playwright.oss.config';

const WORKFLOW_DEMO_MATCH = /.*\/workflow-demo\/.*\.spec\.ts$/;
const WORKFLOW_DEMO_SETUP_MATCH = [
  /.*\/00-bootstrap\.spec\.ts$/,
  /.*\/01-multi-role-users\.spec\.ts$/,
  /.*\/workflow-demo-import\.spec\.ts$/,
];

/**
 * Workflow-demo focused E2E config.
 *
 * Runs the reusable HR leave/BPM scenario suite without pulling unrelated BPM
 * designer or enterprise specs into the same pass.
 *
 * Usage: `pnpm test:workflow-demo`
 */
export default defineConfig({
  ...ossConfig,
  projects: (ossConfig.projects ?? [])
    .filter((project) => project.name !== 'chromium-deep')
    .map((project) => {
      if (project.name === 'setup') {
        return { ...project, testMatch: WORKFLOW_DEMO_SETUP_MATCH };
      }
      if (project.name === 'auth') {
        return project;
      }
      return { ...project, testMatch: WORKFLOW_DEMO_MATCH };
    }),
});
