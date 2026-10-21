import { expect, type Locator, type Page } from '@playwright/test';

/** Open the selected block's JSON editor through its visible disclosure control. */
export async function inspectorJsonField(page: Page, path: string): Promise<Locator> {
  const section = page.getByTestId(`inspector-json-section-${path}`);
  await expect(section).toBeVisible();
  if (!(await section.evaluate((element) => (element as HTMLDetailsElement).open))) {
    await section.locator(':scope > summary').click();
  }
  await expect(section).toHaveAttribute('open', '');
  const field = page.getByTestId(`inspector-field-${path}`);
  await expect(field).toBeVisible();
  return field;
}
