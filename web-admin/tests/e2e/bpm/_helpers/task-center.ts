import { expect, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';
import { loginViaUI } from '../../../helpers/wd-fixtures';

export async function navigateToTaskCenter(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const bpmParent = nav
    .getByRole('button', { name: /流程管理|Process Management/i })
    .first();
  if (await bpmParent.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await bpmParent.scrollIntoViewIfNeeded();
    await bpmParent.evaluate((el: HTMLElement) => el.click());
  }

  const taskCenterLink = nav.locator('a[href*="task-center"]').first();
  await taskCenterLink.waitFor({ state: 'attached', timeout: 8_000 });
  await taskCenterLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForURL(/task-center/, { timeout: 20_000 });
  await expect(page.locator('h1:has-text("任务中心")')).toBeVisible({ timeout: 10_000 });

  const tableOrEmpty = page.locator('table').or(page.locator('text=暂无任务'));
  await expect(tableOrEmpty.first()).toBeVisible({ timeout: 10_000 });
}

export async function openTaskCenterAsRole(
  browser: Browser,
  email: string,
  password: string,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginViaUI(page, email, password);
  await navigateToTaskCenter(page);
  return { context, page };
}

export function findTaskRowByBusinessKey(
  page: Page,
  businessKey: string,
  taskLabel: RegExp,
): Locator {
  return page
    .locator('table tbody tr')
    .filter({
      has: page.locator('[data-testid="task-business-key"]').filter({ hasText: businessKey }),
    })
    .filter({ hasText: taskLabel })
    .first();
}

export async function openTaskRowMenu(taskRow: Locator, page: Page): Promise<Locator> {
  const moreBtn = taskRow
    .locator('button')
    .filter({ has: page.locator('svg.lucide-ellipsis') })
    .first();
  await expect(moreBtn).toBeVisible({ timeout: 5_000 });
  await moreBtn.click();
  const menu = page.locator('.absolute.right-0.z-10').first();
  await expect(menu).toBeVisible({ timeout: 3_000 });
  return menu;
}
