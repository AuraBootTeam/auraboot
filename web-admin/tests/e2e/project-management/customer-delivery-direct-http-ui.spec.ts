import { test, expect, type APIResponse, type Page } from '@playwright/test';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { loginDeliveryMember } from '../../helpers/delivery-session';
import { navigateToMenuByClick } from '../helpers';

const evidence = process.env.AURA_EVIDENCE_DIR!;
const fixturePath = process.env.CUSTOMER_INTEGRATION_FIXTURE;
if (!evidence || !fixturePath) throw Error('Bind the actual customer integration receipt and owned evidence');
test.use({ locale: 'zh-CN', storageState: { cookies: [], origins: [] }, trace: 'off', video: 'off', screenshot: 'off', actionTimeout: 15_000 });
test.setTimeout(240_000);
async function data(response: APIResponse) {
  const body = await response.json(); expect(response.status(), JSON.stringify(body)).toBe(200); expect(String(body.code)).toBe('0'); return body.data;
}
async function shot(page: Page, id: string) {
  await mkdir(join(evidence, 'screenshots'), { recursive: true });
  await expect(page.getByTestId('toast-stack').getByRole('alert')).toHaveCount(0, { timeout: 15_000 });
  for (const error of await page.getByTestId(/automation-(run|action)-error/).all()) {
    await expect(error).toBeVisible();
    expect(await error.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
  }
  const title=page.getByRole('heading',{name:/^编辑自动化:/});
  const actions=page.getByRole('button',{name:'导入流程',exact:true});
  await expect(title).toBeVisible();
  await expect.poll(async()=>{
    const heading=await title.boundingBox();const button=await actions.boundingBox();
    return Boolean(heading&&button&&(heading.y+heading.height<=button.y || heading.x+heading.width<=button.x));
  }).toBe(true);
  await page.screenshot({ path: join(evidence, 'screenshots', id + '.png') });
}

test('direct HTTP actions run from the real designer with resolved payloads and explicit failure and SSRF feedback @critical', async ({ page }) => {
  const fixture = JSON.parse(await readFile(fixturePath!, 'utf8'));
  expect(fixture.status).toBe('passed'); await loginDeliveryMember(page);
  const receipts: Array<{ path: string; projectHeader?: string; body: any; status: number }> = [];
  const cases: Array<Record<string, unknown>> = [];
  const server = createServer(async (request, response) => {
    const buffers: Buffer[] = []; let bytes = 0;
    for await (const part of request) { bytes += part.length; if (bytes > 16_384) { response.writeHead(413); response.end(); return; } buffers.push(Buffer.from(part)); }
    const body = JSON.parse(Buffer.concat(buffers).toString('utf8'));
    const status = request.url!.endsWith('/unavailable') ? 503 : 200;
    receipts.push({ path: request.url!, projectHeader: String(request.headers['x-project-pid']), body, status });
    response.writeHead(status, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ accepted: status === 200, projectPid: body.projectPid, reason: status === 503 ? 'Customer material intake unavailable' : null }));
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = (server.address() as AddressInfo).port;
  const original = await data(await page.request.get(`/api/dynamic/tpm_project/${fixture.projectPid}`));
  let verdict = 'failed';
  try {
    for (const type of ['call_api', 'send_webhook'] as const) {
      for (const variant of ['string', 'object', 'unavailable', 'blocked'] as const) {
        const id = type + '-' + variant;
        const name = `客户交付接口核对 · ${id} · ${Date.now().toString(36)}`;
        const objectPayload = { projectPid: '${recordPid}', amount: '${trigger.amount}', confirmed: '${trigger.confirmed}', source: 'customer-delivery' };
        const payload = variant === 'string' ? JSON.stringify({ projectPid: '${recordPid}', source: 'customer-delivery' }) : objectPayload;
        const config = { url: `http://${variant === 'blocked' ? '127.0.0.2' : 'localhost'}:${port}/direct/` + '${recordPid}/' + variant,
          method: 'post', headers: { 'X-Project-Pid': '${recordPid}' }, timeoutSeconds: 5,
          ...(type === 'call_api' ? { body: payload } : { eventType: 'delivery.materials.checked', payload }) };
        const automation = await data(await page.request.post('/api/automations', { data: {
          name, modelCode: 'tpm_project', triggerType: 'on_record_update', enabled: false,
          triggerConfig: { testRecordPid: fixture.projectPid, testContext: { amount: 128000, confirmed: true } },
          actions: [{ type, config }],
        } }));
        expect(automation.enabled).toBe(false);
        await navigateToMenuByClick(page, ['系统管理', '自动化']);
        await page.getByRole('link', { name, exact: true }).click();
        await expect(page.getByTestId('flow-node-action_0')).toBeVisible();
        await page.getByTestId('flow-node-action_0').click();
        if (id === 'call_api-object') {
          await page.getByTestId('prop-field-body').getByRole('textbox').fill(JSON.stringify({ ...objectPayload, note: '已人工核对材料范围' }));
          await page.getByTestId('prop-field-body').getByRole('textbox').press('Tab');
          const [saved] = await Promise.all([
            page.waitForResponse(r => new URL(r.url()).pathname === `/api/automations/${automation.pid}` && r.request().method() === 'PUT'),
            page.getByTestId('automation-editor-toolbar-btn-save').click(),
          ]);
          expect(saved.status()).toBe(200); await page.reload();
          const persisted = await data(await page.request.get(`/api/automations/${automation.pid}`));
          expect(persisted.flowConfig.nodes.find((node: any) => node.id === 'action_0').data.config.body.note).toBe('已人工核对材料范围');
          await page.getByTestId('flow-node-action_0').click(); await shot(page, 'I12-direct-http-mapping-reopened');
        }
        const before = receipts.length;
        const [fired] = await Promise.all([
          page.waitForResponse(r => new URL(r.url()).pathname === `/api/automations/${automation.pid}/trigger` && r.request().method() === 'POST'),
          page.getByTestId('btn-test-run').click(),
        ]);
        expect(fired.status()).toBe(200);
        expect(fired.request().postDataJSON().recordPid).toBe(fixture.projectPid);
        let log: any;
        await expect.poll(async () => {
          const logs = await data(await page.request.get(`/api/automations/${automation.pid}/logs`));
          expect(logs.length).toBe(1); log = logs[0]; return log.status;
        }, { timeout: 30_000 }).toMatch(/^(success|failed)$/);
        expect(log.status, JSON.stringify(log.actionResults)).toBe(variant === 'blocked' || variant === 'unavailable' ? 'failed' : 'success');
        const result = page.getByTestId('automation-test-run-result');
        await expect(page.getByTestId('automation-run-status')).toContainText(log.status === 'success' ? /成功|Success/i : /失败|Failed/i);
        const action = log.actionResults.find((row: any) => row.actionType === type); expect(action).toBeTruthy();
        if (variant === 'blocked') {
          expect(receipts.length).toBe(before); expect(action.errorMessage).toMatch(/loopback|blocked|not allowed/i);
          await expect(result).toContainText(/loopback|blocked|not allowed/i);
          if (type === 'send_webhook') await shot(page, 'I15-direct-http-security-rejection');
        } else {
          expect(receipts.length).toBe(before + 1); const actual = receipts.at(-1)!;
          expect(actual.path).toBe(`/direct/${fixture.projectPid}/${variant}`);
          expect(actual.projectHeader).toBe(fixture.projectPid);
          expect(actual.body).toMatchObject({ projectPid: fixture.projectPid, source: 'customer-delivery' });
          if (variant !== 'string') expect(actual.body).toMatchObject({ amount: 128000, confirmed: true });
          if (id === 'call_api-object') expect(actual.body.note).toBe('已人工核对材料范围');
          expect(actual.status).toBe(variant === 'unavailable' ? 503 : 200);
          if (variant === 'unavailable') {
            expect(action.status).toBe('failed'); expect(action.errorMessage).toContain('503'); await expect(result).toContainText('503');
            if (type === 'send_webhook') await shot(page, 'I14-direct-http-upstream-failure');
          } else {
            expect(action.result.statusCode).toBe(200);
            await expect(page.getByTestId('automation-action-evidence-statusCode')).toContainText('200');
            if (type === 'send_webhook') expect(action.result.deliveryMode).toBe('direct_http');
            if (id === 'call_api-object') await shot(page, 'I13-direct-http-real-response');
          }
        }
        cases.push({ id, automationPid: automation.pid, logPid: log.pid, status: log.status, verdict: 'passed', requestCount: receipts.length - before });
      }
    }
    expect(cases).toHaveLength(8); expect(receipts).toHaveLength(6);
    const unchanged = await data(await page.request.get(`/api/dynamic/tpm_project/${fixture.projectPid}`));
    expect(unchanged.row_version).toBe(original.row_version); expect(unchanged.tpm_pj_next_action).toBe(original.tpm_pj_next_action);
    verdict = 'passed';
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    await writeFile(join(evidence, 'direct-http-receipt.json'), JSON.stringify({ profile: 'customer-delivery-direct-http-ui-v1', runtime: fixture.runtime, status: verdict, cases, receipts, receiverClosed: true }, null, 2));
  }
});
