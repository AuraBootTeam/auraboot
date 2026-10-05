import { defineConfig } from '@playwright/test';
import base from './playwright.auth-appearance.config';

// Separate explicit policy fixture: the default appearance gate stays closed.
export default defineConfig({ ...base, testMatch: 'auth-appearance-registration.golden.spec.ts' });
