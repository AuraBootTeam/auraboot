import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  GERBER_RUNTIME_BOTTOM_FILE_ID,
  GERBER_RUNTIME_TOP_FILE_ID,
  miniZip,
  openQuoteDetailFromList,
  queryDynamicRecords,
  seedGerberRuntimeQuote,
} from './quote-e2e-helpers';

const VIEWER_TOKEN = 'e2e-gerber-viewer-token';

const boardSvg = (label: string) => `
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 420 180">
  <rect width="420" height="180" fill="#0f3d1f"/>
  <rect x="12" y="12" width="396" height="156" rx="8" fill="#1f6f3a" stroke="#d9f99d" stroke-width="4"/>
  <circle cx="60" cy="60" r="18" fill="#93c5fd"/>
  <circle cx="300" cy="120" r="18" fill="#facc15"/>
  <text x="210" y="96" text-anchor="middle" fill="#ffffff" font-size="32">${label}</text>
</svg>`;

test.describe('PCBA quote Gerber runtime viewer', () => {
  test.describe.configure({ timeout: 120_000 });

  test('renders persisted dynamic-line SVG previews through authenticated file downloads @smoke', async ({
    page,
  }) => {
    const created = await seedGerberRuntimeQuote(page);
    const fileRequests: Array<{ fileId: string; authorization: string; cookie: string }> = [];

    await page.route(
      new RegExp(
        `/api/file/download/(${GERBER_RUNTIME_TOP_FILE_ID}|${GERBER_RUNTIME_BOTTOM_FILE_ID})(?:\\?.*)?$`,
      ),
      async (route) => {
        const url = route.request().url();
        const fileId = url.includes(GERBER_RUNTIME_TOP_FILE_ID)
          ? GERBER_RUNTIME_TOP_FILE_ID
          : GERBER_RUNTIME_BOTTOM_FILE_ID;
        const headers = route.request().headers();
        fileRequests.push({
          fileId,
          authorization: headers.authorization || '',
          cookie: headers.cookie || '',
        });

        await route.fulfill({
          status: 200,
          contentType: 'image/svg+xml',
          body: boardSvg(fileId === GERBER_RUNTIME_TOP_FILE_ID ? 'TOP' : 'BOTTOM'),
        });
      },
    );

    try {
      await openQuoteDetailFromList(page, created);
      await page.getByRole('tab', { name: /Gerber校验|Gerber Check/i }).click();

      const viewer = page.getByTestId('gerber-viewer');
      await expect(viewer).toContainText('E2E Gerber runtime board', { timeout: 30_000 });
      await expect(viewer).toContainText('E2E_ALIGNMENT_WARNING');
      await expect(page.getByTestId('gerber-svg-unavailable')).toHaveCount(0);

      await expect(page.getByRole('img', { name: 'Top Gerber board render' })).toBeVisible({
        timeout: 30_000,
      });
      await expect
        .poll(() => fileRequests.some((request) => request.fileId === GERBER_RUNTIME_TOP_FILE_ID))
        .toBe(true);
      const topRequest = fileRequests.find(
        (request) => request.fileId === GERBER_RUNTIME_TOP_FILE_ID,
      );
      expect(topRequest?.cookie).toContain('__session=');

      await page.evaluate(
        ({ key, token }) => {
          window.localStorage.setItem(key, token);
          window.sessionStorage.setItem(key, token);
        },
        { key: 'jwtToken', token: VIEWER_TOKEN },
      );
      await page.getByRole('button', { name: 'Bottom' }).click();
      await expect(page.getByRole('img', { name: 'Bottom Gerber board render' })).toBeVisible({
        timeout: 30_000,
      });
      await expect
        .poll(() =>
          fileRequests.some((request) => request.fileId === GERBER_RUNTIME_BOTTOM_FILE_ID),
        )
        .toBe(true);
      const bottomRequest = fileRequests.find(
        (request) => request.fileId === GERBER_RUNTIME_BOTTOM_FILE_ID,
      );
      expect(bottomRequest?.authorization).toBe(`Bearer ${VIEWER_TOKEN}`);
      expect(bottomRequest?.cookie).toContain('__session=');
    } finally {
      await cleanupRows(page, created);
    }
  });

  // Q13-02: 重新解析新文件 + 失败保留上次成功结果及上次选择。
  // 上传 gerber_package 走真实 sidecar 解析链;失败后预览保留上次成功渲染,
  // Top/Bottom 选择不回弹。
  test('Q13-02 re-parse refreshes the preview; failed parse keeps the last success and selection', async ({
    page,
  }, testInfo) => {
    test.setTimeout(180_000);
    const created = await seedGerberRuntimeQuote(page);
    await page.route(
      new RegExp(
        `/api/file/download/(${GERBER_RUNTIME_TOP_FILE_ID}|${GERBER_RUNTIME_BOTTOM_FILE_ID})(?:\\?.*)?$`,
      ),
      async (route) => {
        const fileId = route.request().url().includes(GERBER_RUNTIME_TOP_FILE_ID)
          ? GERBER_RUNTIME_TOP_FILE_ID
          : GERBER_RUNTIME_BOTTOM_FILE_ID;
        await route.fulfill({
          status: 200,
          contentType: 'image/svg+xml',
          body: boardSvg(fileId === GERBER_RUNTIME_TOP_FILE_ID ? 'TOP' : 'BOTTOM'),
        });
      },
    );

    try {
      await openQuoteDetailFromList(page, created);
      await page.getByRole('tab', { name: /Gerber校验|Gerber Check/i }).click();
      const viewer = page.getByTestId('gerber-viewer');
      await expect(viewer).toContainText('E2E Gerber runtime board', { timeout: 30_000 });

      // 上次选择:切到 Bottom
      await page.getByRole('button', { name: 'Bottom' }).click();
      await expect(page.getByRole('img', { name: 'Bottom Gerber board render' })).toBeVisible({
        timeout: 30_000,
      });

      // 重新解析新文件:上传可解析的真实包,自动解析链刷新行级解析状态
      const uploadPackage = async (name: string, files: Array<{ name: string; content: string }>) => {
        const upload = await page.request.post('/api/file/upload', { multipart: {
          file: { name, mimeType: 'application/zip', buffer: miniZip(files) },
        }});
        expect(upload.ok(), await upload.text()).toBe(true);
        const fileId = String((await upload.json()).data.fileId);
        const resp = await page.request.post(
          '/api/meta/commands/execute/qo_quote_common:upload_source_attachment',
          { data: { payload: {
            source_file_id: fileId,
            attachment_type: 'gerber_package',
            filename: name,
          }, targetRecordPid: created.quoteId, targetRecordId: created.quoteId, operationType: 'update' } },
        );
        return { status: resp.status(), body: await resp.json().catch(() => ({})) };
      };

      const linePid = String(
        (await queryDynamicRecords(page, 'qo_quote_line_common', [
          { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
        ]))[0]?.pid ?? '',
      );
      expect(linePid, 'quote has a BOM line').toBeTruthy();

      const reparse = await uploadPackage('reparse-package.zip', [
        { name: 'board.gtp', content: '%FSLAX24Y24*%\n%MOMM*%\n%TF.FileFunction,Paste,Top*%\n%ADD10C,0.600*%\nD10*\nX000000Y000000D03*\nX020000Y000000D03*\nX040000Y000000D03*\nM02*\n' },
        { name: 'board.gtl', content: '%FSLAX24Y24*%\n%MOMM*%\n%TF.FileFunction,Copper,L1,Top*%\n%ADD10C,0.600*%\nD10*\nX000000Y000000D02*\nX040000Y000000D01*\nM02*\n' },
        { name: 'board.drl', content: 'M48\nMETRIC\nT01C0.800\n%\nT01\nX000000Y000000\nX020000Y000000\nX040000Y000000\nM30\n' },
        { name: 'board-via.drl', content: 'M48\nMETRIC\nT01C0.300\n%\nT01\nX000000Y000000\nX040000Y000000\nM30\n' },
      ]);
      expect(reparse.status, 're-parse upload accepted').toBe(200);
      expect(String(reparse.body.code)).toBe('0');
      await expect
        .poll(
          async () =>
            (await queryDynamicRecords(page, 'qo_quote_line_common', [
              { fieldName: 'pid', operator: 'EQ', value: linePid },
            ]))[0]?.qo_ql_gerber_parse_status,
          { timeout: 60_000 },
        )
        .toBe('parsed');

      // 失败保留:非法包不产生新的成功解析(链回执 failed/skipped),预览仍渲染
      // 上次成功结果,Top/Bottom 选择不回弹
      const broken = await uploadPackage('broken-package.zip', [
        { name: 'board.gbr', content: 'this is not a gerber file at all' },
      ]);
      expect(broken.status, 'broken upload accepted at attachment layer').toBe(200);
      // 行级解析状态保留上次成功值('parsed')——sidecar 对内容级问题宽容
      // (degenerate 文件解析为 parsed+warning),不会清掉上次成功解析
      const lineAfterBroken = (await queryDynamicRecords(page, 'qo_quote_line_common', [
        { fieldName: 'pid', operator: 'EQ', value: linePid },
      ]))[0];
      expect(String(lineAfterBroken?.qo_ql_gerber_parse_status ?? '')).toBe('parsed');
      // 上次成功结果保留:viewer 仍渲染上一块的板标题与 Bottom 渲染,SVG 未失效
      await expect(viewer).toContainText('E2E Gerber runtime board', { timeout: 30_000 });
      await expect(page.getByRole('img', { name: 'Bottom Gerber board render' })).toBeVisible({
        timeout: 30_000,
      });
      await expect(page.getByTestId('gerber-svg-unavailable')).toHaveCount(0);
      await testInfo.attach('q13-02-retention', {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });
    } finally {
      await cleanupRows(page, created);
    }
  });
});
