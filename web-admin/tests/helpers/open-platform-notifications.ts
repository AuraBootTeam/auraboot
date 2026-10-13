import { expect, type Locator, type Page, type TestInfo } from '@playwright/test';
import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { once } from 'node:events';
import { randomBytes } from 'node:crypto';
import { mkdir, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE_URL as WEB_BASE_URL } from './environments';

const coreRoot = fileURLToPath(new URL('../../../', import.meta.url));
interface Delivery { pid: string; eventId: string; status: string; replayCount: number; responseStatus: number | null; lastAttemptAt: string; failureReason: string | null }
const translations: Record<string, string> = {
  '模型通知': 'Model notifications', '订阅名称': 'Subscription name', '模型事件': 'Model event', '事件版本': 'Event version',
  '接收地址': 'Receiver URL', '签名密钥': 'Signing secret', '最大尝试次数': 'Maximum attempts', '保存通知订阅': 'Save notification subscription',
  '订阅已保存': 'Subscription saved', '关闭通知编辑': 'Close notification editor', '编辑订阅': 'Edit subscription',
  '运维': 'Operations', '重放': 'Replay', '确认重放': 'Confirm replay', '停用订阅': 'Disable subscription',
  '通知操作确认': 'Notification confirmation', '确认': 'Confirm', '订阅已停用': 'Subscription disabled',
};

/** Extends the original installation journey; no route interception or API fallback for UI writes. */
export async function verifyModelNotifications({ page, card, installationPid, resourceCode, resourcePath, machineHeaders,
  locale, evidenceRoot, testInfo, revoke }: {
  page: Page; card: Locator; installationPid: string; resourceCode: string; resourcePath: string; machineHeaders: Record<string, string>;
  locale: string; evidenceRoot: string; testInfo: TestInfo; revoke: () => Promise<void>;
}) {
  const text = (cn: string) => locale.startsWith('zh') ? cn : translations[cn];
  const runId = `ui-${Date.now()}-${randomBytes(4).toString('hex')}`;
  const directory = path.join(evidenceRoot, 'model-notifications'); await mkdir(directory, { recursive: true, mode: 0o700 });
  const secret = randomBytes(32).toString('hex'); const secretFile = path.join(directory, 'signing-key');
  await writeFile(secretFile, secret, { mode: 0o600, flag: 'wx' });
  const children: ChildProcess[] = [];
  const start = async (mode: 'accept' | 'fail-all') => {
    const store = path.join(directory, `${mode}.sqlite`); const receiverRun = `${runId}-${mode}`;
    const child = spawn('python3', ['-B', path.join(coreRoot, 'scripts/ci/open_platform_receiver.py'), '--secret-file', secretFile,
      '--store', store, '--run-id', receiverRun, '--port', '0', '--mode', mode], { stdio: ['ignore', 'pipe', 'pipe'] }); children.push(child);
    const ready = await new Promise<{ port: number }>((resolve, reject) => {
      let output = ''; const timeout = setTimeout(() => reject(new Error('Owned notification receiver did not become ready')), 10000);
      child.on('error', reject); child.on('exit', code => { clearTimeout(timeout); reject(new Error(`Owned notification receiver exited: ${code}`)); });
      child.stdout!.on('data', chunk => { output += chunk.toString(); if (output.includes('\n')) { clearTimeout(timeout);
        try { const value = JSON.parse(output.split('\n')[0]); if (value.ready !== true || !Number.isInteger(value.port) || value.port < 1) throw new Error('Invalid receiver identity'); resolve(value); }
        catch (cause) { reject(cause); }
      } });
    });
    return { store, mode, runId: receiverRun, url: `http://127.0.0.1:${ready.port}/webhooks/${receiverRun}` };
  };
  const human = async (url: string) => { const response = await page.request.get(`${WEB_BASE_URL}${url}`); expect(response.status()).toBe(200);
    const body = await response.json(); expect(String(body.code)).toBe('0'); return body.data; };
  const editor = page.getByTestId('installation-notification-editor'); const panel = page.getByTestId('open-platform-operations-panel');
  const image = async (id: string, locator = editor.getByTestId('notification-subscriptions')) => {
    await locator.evaluate(element => element.scrollIntoView({ block: 'center', inline: 'nearest' }));
    await expect(locator).toBeInViewport({ ratio: 1 });
    await locator.screenshot({ path: path.join(evidenceRoot, `${id}.png`), timeout: 30000 }); };
  try {
    const accepting = await start('accept'); const failing = await start('fail-all');
    await card.getByRole('button', { name: text('模型通知'), exact: true }).click();
    await expect(editor.getByLabel(text('模型事件'), { exact: true })).toBeVisible();
    await expect(editor.getByRole('alert')).toHaveCount(0); await image('publication-subscribe-empty');
    const name = `ERP model notifications ${runId}`; const eventType = `open.resource.${resourceCode}.created`;
    await editor.getByLabel(text('订阅名称'), { exact: true }).fill(name);
    await editor.getByLabel(text('模型事件'), { exact: true }).selectOption(eventType);
    await expect(editor.getByLabel(text('事件版本'), { exact: true })).toHaveValue('1');
    await editor.getByLabel(text('接收地址'), { exact: true }).fill(accepting.url);
    await editor.getByLabel(text('最大尝试次数'), { exact: true }).fill('1');
    await editor.getByRole('button', { name: text('保存通知订阅'), exact: true }).click();
    expect(await editor.getByLabel(text('签名密钥'), { exact: true }).evaluate((element: HTMLInputElement) => element.validity.valueMissing)).toBe(true);
    expect((await human('/api/webhooks')).filter((row: { name: string }) => row.name === name)).toEqual([]);
    await image('publication-subscribe-validation', editor.getByTestId('notification-receiver-fields'));
    await editor.getByLabel(text('签名密钥'), { exact: true }).fill(secret);
    const savedResponse = page.waitForResponse(response => response.url().endsWith('/api/webhooks') && response.request().method() === 'POST');
    await editor.getByRole('button', { name: text('保存通知订阅'), exact: true }).click();
    const saved = await savedResponse; expect(saved.status()).toBe(200);
    const payload = saved.request().postDataJSON(); expect(payload.installationPid).toBe(installationPid); expect(payload.eventType).toBe(eventType);
    expect(payload.eventVersion).toBe(1); expect(payload.targetUrl).toBe(accepting.url); expect(payload.secret === secret, 'The UI must send the agreed signing secret').toBe(true);
    const subscription = (await saved.json()).data; expect(subscription.installationPid).toBe(installationPid);
    await expect(editor.getByRole('status').filter({ hasText: text('订阅已保存') })).toBeVisible();
    await expect(editor.getByLabel(text('签名密钥'), { exact: true })).toHaveValue(''); await image('publication-subscribe');
    await expect(editor.getByTestId('notification-current-event')).toContainText(locale.startsWith('zh') ? '新增记录' : 'Record created');
    await image('publication-subscribe-contract', editor.getByTestId('notification-contract-fields'));
    await image('publication-subscribe-security', editor.getByTestId('notification-receiver-fields'));
    await image('publication-subscribe-actions', editor.getByTestId('notification-actions'));
    await editor.getByRole('button', { name: text('关闭通知编辑'), exact: true }).click();
    await card.getByRole('button', { name: text('模型通知'), exact: true }).click();
    const row = editor.getByTestId(`notification-${subscription.pid}`); await expect(row).toContainText(name); await expect(row).toContainText(accepting.url);
    await row.getByRole('button', { name: text('编辑订阅'), exact: true }).click();
    await expect(editor.getByLabel(text('接收地址'), { exact: true })).toHaveValue(accepting.url); await image('publication-subscribe-reloaded');
    const retarget = async (url: string) => {
      await editor.getByLabel(text('接收地址'), { exact: true }).fill(url);
      const changed = page.waitForResponse(response => response.url().endsWith(`/api/webhooks/${subscription.pid}`) && response.request().method() === 'PUT');
      await editor.getByRole('button', { name: text('保存通知订阅'), exact: true }).click(); const response = await changed;
      expect(response.status()).toBe(200); expect(response.request().postDataJSON().installationPid).toBe(installationPid);
      await expect(editor.getByRole('status').filter({ hasText: text('订阅已保存') })).toBeVisible();
      const persisted = await human(`/api/webhooks/${subscription.pid}`); expect(persisted.targetUrl).toBe(url); expect(persisted.enabled).toBe(true);
    };
    const create = async (purpose: string) => {
      const origin = `${runId}-${purpose.replace(/[^a-zA-Z0-9_-]/g, '-')}`; const intent = { name: purpose, zone: 'A' };
      const response = await page.request.post(resourcePath, { headers: { ...machineHeaders, 'X-Request-Id': origin, 'Idempotency-Key': origin }, data: intent });
      expect(response.status()).toBe(201); const created = await response.json(); expect(created).toMatchObject(intent);
      return { subject: created.pid as string, origin, projection: { ...intent, pid: created.pid } };
    };
    const delivery = async (origin: string, status: string, replayCount = 0) => {
      let found: Delivery | undefined;
      await expect.poll(async () => {
        const rows = await human(`/api/open-platform/installations/${installationPid}/webhook-deliveries`);
        const matches = rows.filter((item: Delivery & { requestId: string }) => item.requestId === origin);
        expect(matches.length).toBeLessThanOrEqual(1); found = matches[0];
        return found?.status === status && found?.replayCount === replayCount && !!found?.lastAttemptAt;
      }, { timeout: 45000, intervals: [100, 250, 500, 1000] }).toBe(true);
      return found!;
    };
    const verify = (receiver: typeof accepting, log: Delivery, source: Awaited<ReturnType<typeof create>>, statuses: number[], tamper = false) => {
      const result = spawnSync('python3', ['-B', '-c', `import json,sys\nsys.path.insert(0,sys.argv[1])\nfrom open_platform_webhook_evidence import read_delivery,verify_delivery\nx=json.load(sys.stdin); key=x.pop('secret').encode(); tamper=x.pop('tamper'); rows=read_delivery(x.pop('store'),x['delivery_pid'],secret=key,run_id=x.pop('run_id'),mode=x.pop('mode'),failures=1)\nif tamper: rows[0]['signature']='sha256='+'0'*64\nproof=verify_delivery(rows,secret=key,**x)\nprint(json.dumps(proof))`, path.join(coreRoot, 'scripts/ci')], {
        input: JSON.stringify({ secret, tamper, store: receiver.store, run_id: receiver.runId, mode: receiver.mode, delivery_pid: log.pid, event_id: log.eventId,
          subject_pid: source.subject, statuses, originating_request_id: source.origin, event_type: eventType, subject_type: resourceCode, schema_version: 1,
          projections: statuses.map(() => source.projection) }), encoding: 'utf8', timeout: 10000,
      });
      if (tamper) { expect(result.status, 'A modified raw signature must be rejected').toBe(1);
        expect(result.stderr).toContain('independent raw HMAC verification failed'); return null; }
      expect(result.status, 'Independent raw-byte HMAC, projection and source identity must match').toBe(0);
      return JSON.parse(result.stdout);
    };
    const successSource = await create('Signed UI delivery'); const success = await delivery(successSource.origin, 'success');
    const successProof = verify(accepting, success, successSource, [200]);
    verify(accepting, success, successSource, [200], true);
    await card.getByRole('button', { name: text('运维'), exact: true }).click();
    await expect(panel).toContainText(success.eventId); await image('publication-delivery', panel.getByTestId(`webhook-delivery-${success.pid}`));
    await retarget(failing.url); const failedSource = await create('UI dead letter'); const dead = await delivery(failedSource.origin, 'dead_letter');
    expect(dead.responseStatus).toBe(503); const deadProof = verify(failing, dead, failedSource, [503]);
    await panel.getByTestId('open-platform-operations-refresh').click(); await expect(panel).toContainText(dead.eventId); await image('publication-dead-letter', panel.getByTestId(`webhook-delivery-${dead.pid}`));
    await retarget(accepting.url);
    const replay = async (log: Delivery, capture: string) => {
      await panel.getByTestId('open-platform-operations-refresh').click();
      const deliveryRow = panel.getByTestId(`webhook-delivery-${log.pid}`);
      await deliveryRow.getByRole('button', { name: text('重放'), exact: true }).click();
      const confirmation = panel.getByRole('alert'); await expect(confirmation).toBeVisible(); await image(capture, confirmation);
      const response = page.waitForResponse(reply => reply.url().endsWith(`/webhook-deliveries/${log.pid}/replay`) && reply.request().method() === 'POST');
      await confirmation.getByRole('button', { name: text('确认重放'), exact: true }).click(); expect((await response).status()).toBe(200);
    };
    await replay(dead, 'publication-replay-confirm'); const recovered = await delivery(failedSource.origin, 'success', 1);
    expect(recovered.pid).toBe(dead.pid); expect(recovered.eventId).toBe(dead.eventId);
    const replayProof = verify(accepting, recovered, failedSource, [200]);
    await panel.getByTestId('open-platform-operations-refresh').click(); await expect(panel).toContainText(recovered.eventId); await image('publication-replay', panel.getByTestId(`webhook-delivery-${recovered.pid}`));
    await retarget(failing.url); const revokedSource = await create('Revoked UI replay'); const revoked = await delivery(revokedSource.origin, 'dead_letter');
    const beforeRevocation = verify(failing, revoked, revokedSource, [503]);
    await revoke(); await retarget(accepting.url);
    await replay(revoked, 'publication-replay-revoked-confirm'); const denied = await delivery(revokedSource.origin, 'dead_letter', 1);
    expect(denied.pid).toBe(revoked.pid); expect(denied.eventId).toBe(revoked.eventId); expect(denied.responseStatus).toBeNull();
    expect(denied.failureReason).toBe('authority_revoked');
    const absence = spawnSync('python3', ['-B', '-c', 'import sqlite3,sys,json\nwith sqlite3.connect(sys.argv[1]) as db: print(json.dumps(db.execute("SELECT COUNT(*) FROM deliveries WHERE delivery_id=?",(sys.argv[2],)).fetchone()[0]))', accepting.store, revoked.pid], { encoding: 'utf8', timeout: 10000 });
    expect(absence.status).toBe(0); expect(JSON.parse(absence.stdout)).toBe(0); verify(failing, revoked, revokedSource, [503]);
    await panel.getByTestId('open-platform-operations-refresh').click(); await expect(panel).toContainText(revoked.eventId); await image('publication-replay-revoked', panel.getByTestId(`webhook-delivery-${revoked.pid}`));
    await expect(panel.getByTestId(`webhook-delivery-${revoked.pid}`)).toContainText(locale.startsWith('zh')
      ? '通知未发送。恢复授权后再重放。' : 'No notification was sent. Restore authority before replaying.');
    await row.getByRole('button', { name: text('停用订阅'), exact: true }).click();
    const disableConfirmation = editor.getByRole('group', { name: text('通知操作确认'), exact: true }); await image('publication-subscribe-disable-confirm', disableConfirmation);
    await disableConfirmation.getByRole('button', { name: text('确认'), exact: true }).click();
    await expect(editor.getByRole('status').filter({ hasText: text('订阅已停用') })).toBeVisible(); expect((await human(`/api/webhooks/${subscription.pid}`)).enabled).toBe(false);
    await image('publication-subscribe-disabled');
    const receipt = { schemaVersion: 1, installationPid, subscriptionPid: subscription.pid, resourceCode, eventType, eventVersion: 1,
      cases: ['ui-subscription-persisted', 'signed-real-delivery', 'real-dead-letter', 'confirmed-same-event-replay', 'revoked-authority-no-send', 'ui-subscription-disabled'],
      deliveryProofs: [successProof, deadProof, replayProof, beforeRevocation], revokedReplay: { ...denied, acceptingReceiverAttempts: 0 },
      fixture: 'original-installation-ui-authored-grant', routeMocks: false };
    const receiptPath = path.join(directory, 'receipt.json'); await writeFile(receiptPath, JSON.stringify(receipt, null, 2));
    await testInfo.attach('dynamic-model-notifications', { path: receiptPath, contentType: 'application/json' });
  } finally {
    for (const child of children) { if (child.exitCode === null && child.signalCode === null) { const closed = once(child, 'exit'); child.kill('SIGTERM'); await closed; } }
    await unlink(secretFile);
  }
}
