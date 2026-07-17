import type { Page } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { test, expect } from '../../fixtures';
import { BACKEND_URL } from '../../helpers/environments';
import { uniqueId } from '../helpers';

async function backendAuthHeaders(page: Page): Promise<Record<string, string>> {
  const sessionCookie = (await page.context().cookies()).find(
    (cookie) => cookie.name === '__session',
  );
  expect(sessionCookie, 'backend auth requires the stored session cookie').toBeTruthy();
  const sessionPayload = decodeURIComponent(sessionCookie!.value).split('.')[0];
  const session = JSON.parse(Buffer.from(sessionPayload, 'base64').toString('utf8')) as {
    jwtToken?: string;
  };
  expect(session.jwtToken, 'backend auth requires a JWT token in the session cookie').toBeTruthy();
  return { Authorization: `Bearer ${session.jwtToken}` };
}

async function publishPage(page: Page, payload: Record<string, unknown>) {
  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create upload artifact page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created page pid').toBeTruthy();

  const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
  expect(publishResp.ok(), `Publish upload artifact page failed: ${publishResp.status()}`).toBeTruthy();
  const publishBody = await publishResp.json();
  expect(publishBody.code, 'publish page API code').toBe('0');
  expect(publishBody.data?.status, 'published page status').toBe('published');

  return pid;
}

async function createUploadFormPage(page: Page) {
  const id = uniqueId('pd_upload_artifact');
  const pageKey = id.replace(/-/g, '_');
  const title = `Upload artifact runtime ${id}`;
  const field = {
    field: 'runtime_attachment',
    label: 'Runtime attachment',
    component: 'SmartUpload',
    props: {
      accept: '.zip,application/zip',
      multiple: false,
      maxCount: 1,
      maxSize: 1,
      listType: 'text',
      buttonText: 'Upload artifact',
      hint: 'ZIP up to 1MB',
    },
    layout: { colSpan: 12 },
  };
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'form',
    modelCode: 'page_schema',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    blocks: [
      {
        id: 'upload_section',
        blockType: 'form-section',
        title: 'Upload artifact section',
        fields: [field],
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, uploadArtifact: true },
    semver: '0.1.0',
  };

  const pid = await publishPage(page, payload);
  return { pid, pageKey, title, field };
}

test.describe('Page Designer upload attachment artifact runtime', () => {
  test('SmartUpload enforces constraints, downloads uploaded bytes, and deletes the file API record', async ({
    page,
  }, testInfo) => {
    const { pid, pageKey, field } = await createUploadFormPage(page);
    const readbackResp = await page.request.get(`/api/pages/${pid}`);
    expect(readbackResp.ok(), `Readback upload page failed: ${readbackResp.status()}`).toBeTruthy();
    const readback = await readbackResp.json();
    expect(readback.code, 'readback upload page code').toBe('0');
    expect(readback.data?.blocks?.[0]?.fields?.[0], 'upload field binding persisted').toMatchObject(
      field,
    );

    const rejectedPath = path.join(testInfo.outputDir, 'designer-upload-too-large.zip');
    const uploadPath = path.join(testInfo.outputDir, 'designer-upload-proof.zip');
    const uploadedBytes = Buffer.from('PK\x03\x04designer upload proof bytes\n', 'utf8');
    await mkdir(testInfo.outputDir, { recursive: true });
    await writeFile(rejectedPath, Buffer.alloc(1024 * 1024 + 32, 'x'));
    await writeFile(uploadPath, uploadedBytes);

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-form')).toBeVisible({ timeout: 15_000 });
    const attachmentField = page.getByTestId('field-runtime_attachment');
    await expect(attachmentField).toBeVisible();
    const input = attachmentField.locator('[data-testid="upload-input-runtime_attachment"]');
    await expect(input).toBeAttached({ timeout: 10_000 });
    await expect(input).toHaveAttribute('accept', '.zip,application/zip');

    const uploadRequests: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().includes('/api/file/upload')) {
        uploadRequests.push(request.url());
      }
    });

    await input.setInputFiles(rejectedPath);
    // The oversized file is rejected inline (standard §4: inline validation, not
    // toast) — surfaced in the dedicated rejections panel with the size-limit
    // reason, never added to the upload list nor sent to the upload API.
    const rejections = attachmentField.getByTestId('upload-rejections-runtime_attachment');
    await expect(rejections).toContainText('designer-upload-too-large.zip');
    await expect(rejections).toContainText('1MB');
    await expect(attachmentField.getByTestId('upload-file-runtime_attachment')).toHaveCount(0);
    expect(uploadRequests, 'oversized file must be rejected before upload API').toHaveLength(0);

    const uploadRespPromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && response.url().includes('/api/file/upload'),
      { timeout: 20_000 },
    );
    await input.setInputFiles(uploadPath);
    const uploadResp = await uploadRespPromise;
    expect(uploadResp.ok(), `upload response status ${uploadResp.status()}`).toBeTruthy();
    const uploadBody = await uploadResp.json();
    expect(uploadBody.code, 'upload API code').toBe('0');
    const fileId = String(uploadBody.data?.fileId ?? '');
    expect(fileId, 'upload API returns fileId pid for delete/download').toBeTruthy();
    expect(uploadBody.data?.originalName, 'upload original filename').toBe(
      'designer-upload-proof.zip',
    );

    await expect(attachmentField).toContainText('designer-upload-proof.zip', { timeout: 10_000 });
    // The upload upgrade (#708) localized the count hint (was "1/1 files uploaded").
    await expect(attachmentField).toContainText('已上传 1/1');
    await expect(page.getByTestId('upload-area-runtime_attachment')).toHaveCount(0);

    await attachmentField.getByText('designer-upload-proof.zip').click();
    await expect(page.getByTestId('file-preview-modal')).toBeVisible({ timeout: 5_000 });
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('link', { name: /^Download$/ }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('designer-upload-proof.zip');
    const downloadedPath = path.join(testInfo.outputDir, download.suggestedFilename());
    await download.saveAs(downloadedPath);
    expect(await readFile(downloadedPath)).toEqual(uploadedBytes);

    await page.keyboard.press('Escape');
    await expect(page.getByTestId('file-preview-modal')).toHaveCount(0);

    const deleteRespPromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'DELETE' &&
        response.url().includes(`/api/file/${encodeURIComponent(fileId)}`),
      { timeout: 20_000 },
    );
    await attachmentField.locator('[data-testid="btn-remove-file"]').first().click({ force: true });
    const deleteResp = await deleteRespPromise;
    expect(deleteResp.ok(), `delete response status ${deleteResp.status()}`).toBeTruthy();
    const deleteBody = await deleteResp.json();
    expect(deleteBody.code, 'delete API code').toBe('0');
    expect(deleteBody.data, 'delete API result').toBe(true);

    await expect(attachmentField).not.toContainText('designer-upload-proof.zip');
    const afterDelete = await page.request.get(`${BACKEND_URL}/api/file/download/${fileId}`, {
      headers: await backendAuthHeaders(page),
    });
    expect(afterDelete.status(), 'deleted file cannot be downloaded').toBe(404);
  });
});
