import { defineConfig } from '@playwright/test';
import baseConfig from './playwright.config';

// This workload owns an exact data set; the general OSS suite shares mutable fixtures.
const storageState =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR ? `${process.env.PW_STORAGE_DIR}/admin.json` : undefined);
if (!storageState) {
  throw new Error(
    'Performance browser tests require private PW_ADMIN_STORAGE_STATE or PW_STORAGE_DIR',
  );
}
if (!process.env.PERF_EVIDENCE_DIR) {
  throw new Error('Performance browser tests require PERF_EVIDENCE_DIR');
}

export default defineConfig({
  ...baseConfig,
  workers: 1,
  retries: 0,
  use: { ...baseConfig.use, storageState },
  projects: [
    {
      name: 'performance',
      testMatch: /tests\/e2e\/showcase\/oss-performance\.spec\.ts$/,
    },
  ],
});
