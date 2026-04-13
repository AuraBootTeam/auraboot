import fs from 'fs/promises';
import path from 'path';
import type { Page, TestInfo } from '@playwright/test';

const COVERAGE_DIR = path.resolve(process.cwd(), 'test-results/coverage/raw');

function sanitizeFileName(input: string): string {
  return input.replace(/[^a-zA-Z0-9._-]+/g, '_');
}

export async function writeIstanbulCoverage(page: Page, testInfo: TestInfo): Promise<void> {
  if (process.env.E2E_COVERAGE !== '1') return;

  const coverage = await page
    .evaluate(() => (window as any).__coverage__ || null)
    .catch(() => null);
  if (!coverage || Object.keys(coverage).length === 0) return;

  await fs.mkdir(COVERAGE_DIR, { recursive: true });
  const fileName = sanitizeFileName(
    `${testInfo.project.name}-${testInfo.title}-${Date.now()}.json`,
  );
  const fullPath = path.join(COVERAGE_DIR, fileName);
  await fs.writeFile(fullPath, JSON.stringify(coverage), 'utf-8');
}
