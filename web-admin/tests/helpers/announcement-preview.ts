import { expect, type Page } from '@playwright/test';

/** Wait for the actual business content, not only the independently loaded facts table. */
export async function expectAnnouncementReminderPreviewReady(page: Page) {
  const guidance = page.locator('[data-aura-block-id="announcement_reminder_preview_detail_guidance"]');
  const information = page.getByTestId('record-inspector');
  const facts = page.locator('[data-aura-block-id="announcement_reminder_preview_detail_facts"]');
  await expect(guidance).toContainText('预览有效 30 分钟');
  await expect(information).toContainText('催办信息');
  await expect(information.getByText('可提交', { exact: true })).toBeVisible();
  await expect(information).toContainText('知悉截止');
  await expect(information).toContainText('姓名筛选');
  await expect(information).toContainText('状态筛选');
  await expect(facts.getByRole('columnheader', { name: '当前可催办', exact: true })).toBeVisible();
  await expect(facts.getByRole('cell', { name: '1', exact: true })).toHaveCount(2);
  const boxes = await Promise.all([guidance.boundingBox(), information.boundingBox(), facts.boundingBox()]);
  expect(boxes.every(Boolean), 'all preview groups must be rendered before capture').toBe(true);
  for (let index = 1; index < boxes.length; index += 1) {
    const previous = boxes[index - 1]!;
    const gap = boxes[index]!.y - previous.y - previous.height;
    expect(gap, 'adjacent preview groups must not contain empty loading reservations').toBeGreaterThanOrEqual(0);
    expect(gap, 'adjacent preview groups keep normal stack spacing').toBeLessThanOrEqual(24);
  }
}
