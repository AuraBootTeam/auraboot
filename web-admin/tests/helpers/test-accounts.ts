/**
 * Centralized test account credentials.
 * Used across all E2E and API tests.
 */
export const TEST_ACCOUNTS = {
  admin: {
    email: 'admin@auraboot.test',
    password: 'Test2026x',
    name: 'Test Admin',
  },
} as const;

export const DEFAULT_TEST_ACCOUNT = TEST_ACCOUNTS.admin;
