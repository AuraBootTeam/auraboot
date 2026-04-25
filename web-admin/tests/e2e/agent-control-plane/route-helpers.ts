import { expect, type Page } from '@playwright/test';

export function toAcpUiPath(path: string): string {
  if (!path.startsWith('/dynamic/')) {
    return path;
  }
  const match = path.match(/^\/dynamic\/([^/]+)(.*)$/);
  if (!match) {
    return path.replace(/^\/dynamic\//, '/p/');
  }
  const [, modelCode, rest] = match;
  return `/p/${modelCode.replace(/-/g, '_')}${rest}`;
}

export async function gotoAcpUiPage(page: Page, path: string): Promise<void> {
  const target = toAcpUiPath(path);
  await page.goto(target, { waitUntil: 'domcontentloaded' });
  await expectAcpUiPage(page, path);
}

export async function expectAcpUiPage(
  page: Page,
  path: string,
  timeout = 10_000,
): Promise<void> {
  const target = toAcpUiPath(path);
  await expect
    .poll(
      async () => {
        const pathname = new URL(page.url()).pathname;
        return (
          pathname === target ||
          pathname.startsWith(`${target}/`) ||
          pathname === path ||
          pathname.startsWith(`${path}/`)
        );
      },
      { timeout },
    )
    .toBe(true);
}
