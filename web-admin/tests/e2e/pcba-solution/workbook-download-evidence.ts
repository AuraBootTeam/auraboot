import { expect, type Download, type TestInfo } from '@playwright/test';

/** Attach only a completed browser download; upload fixtures cannot satisfy this contract. */
export async function saveWorkbookDownload(download: Download, target: string, info: TestInfo, scenario: string) {
  expect(await download.failure(), `download ${scenario}`).toBeNull();
  expect(download.suggestedFilename()).toMatch(/\.xlsx$/i);
  await download.saveAs(target);
  await info.attach(`workbook-download:${scenario}`, {
    path: target,
    contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
}
