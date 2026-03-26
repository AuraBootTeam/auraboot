/**
 * Centralized test account credentials.
 * Used across all E2E and API tests.
 */
export const TEST_ACCOUNTS = {
  admin: {
    email: process.env.TEST_ADMIN_EMAIL || 'e2e@test.local',
    password: process.env.TEST_ADMIN_PASSWORD || 'E2eTestPass2026!',
    name: 'Test Admin',
  },
} as const;

export const DEFAULT_TEST_ACCOUNT = TEST_ACCOUNTS.admin;
