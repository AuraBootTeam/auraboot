/**
 * Centralized test account credentials.
 * Used across all E2E and API tests.
 */
export const TEST_ACCOUNTS = {
  admin: {
    email: process.env.PW_ADMIN_EMAIL ?? process.env.ADMIN_EMAIL ?? 'admin@auraboot.com',
    password: process.env.PW_ADMIN_PASSWORD ?? process.env.ADMIN_PASSWORD ?? 'Test2026x',
    name: 'Admin',
  },
} as const;

export const DEFAULT_TEST_ACCOUNT = TEST_ACCOUNTS.admin;
