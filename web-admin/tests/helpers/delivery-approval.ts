import { expect, type Page } from '@playwright/test';

/** Drive the supported bound-form drawer or the unbound decision dialog. */
export async function completeDeliveryApproval(
  page: Page,
  action: 'approve' | 'reject',
  comment: string,
) {
  const submitted = page.waitForResponse(response => {
    const pathname = new URL(response.url()).pathname;
    return response.request().method() === 'POST' &&
      (new RegExp(`/api/bpm/tasks/[^/]+/${action}$`).test(pathname) ||
        /\/api\/bpm\/forms\/task\/[^/]+\/submit$/.test(pathname));
  });
  await page.getByTestId(`bpm-operations-${action}`).click();
  const drawer = page.getByTestId('bpm-task-drawer');
  await expect(drawer.or(page.getByTestId(`bpm-${action}-comment`))).toBeVisible();
  if (await drawer.count()) {
    await page.getByTestId('approval-comment-textarea').fill(comment);
    const button = page.getByTestId(`bpm-drawer-${action}-btn`);
    await button.scrollIntoViewIfNeeded();
    const footer = await page.getByTestId('bpm-drawer-footer').boundingBox();
    expect(footer).toBeTruthy();
    expect(footer!.y).toBeGreaterThanOrEqual(0);
    expect(footer!.y + footer!.height).toBeLessThanOrEqual(page.viewportSize()!.height);
    await button.click({ timeout: 10000 });
  } else {
    await page.getByTestId(`bpm-${action}-comment`).fill(comment);
    await page.getByTestId(`bpm-${action}-confirm`).click();
  }
  return submitted;
}
