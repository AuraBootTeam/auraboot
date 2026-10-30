/** S1 real command pipeline, persistence and sole-writer authorization. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { BASE_URL, PG_CONN } from '../helpers/environments';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  extraHTTPHeaders: { Referer: `${BASE_URL}/` },
});

test('draft commands enforce replay, revision, protected fields and audit atomicity', async ({
  request,
}) => {
  const operation = randomUUID();
  const input = {
    clientRequestId: operation,
    operationType: 'CREATE',
    payload: { title: `Controlled ${operation}` },
  };
  const create = await request.post('/api/meta/commands/execute/announcement:create_announcement', {
    data: input,
  });
  expect(create.status(), await create.text()).toBe(200);
  const created = (await create.json()).data.data;
  const pid = created.pid;
  expect(pid).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
  expect(created.status).toBe('draft');
  const replay = await request.post('/api/meta/commands/execute/announcement:create_announcement', {
    data: input,
  });
  expect((await replay.json()).data.data).toEqual(created);
  const changedIntent = await request.post(
    '/api/meta/commands/execute/announcement:create_announcement',
    {
      data: { ...input, payload: { title: 'Different intent' } },
    },
  );
  expect(changedIntent.status(), await changedIntent.text()).toBe(409);

  const update = await request.post('/api/meta/commands/execute/announcement:update_announcement', {
    data: {
      targetRecordPid: pid,
      clientRequestId: randomUUID(),
      expectedVersion: 1,
      operationType: 'UPDATE',
      payload: { title: 'Saved revision', pinned: true },
    },
  });
  expect(update.status(), await update.text()).toBe(200);
  expect((await update.json()).data.data.row_version).toBe(2);
  const stale = await request.post('/api/meta/commands/execute/announcement:update_announcement', {
    data: {
      targetRecordPid: pid,
      clientRequestId: randomUUID(),
      expectedVersion: 1,
      operationType: 'UPDATE',
      payload: { title: 'Stale overwrite' },
    },
  });
  expect(stale.status(), await stale.text()).toBe(409);
  const injected = await request.post(
    '/api/meta/commands/execute/announcement:update_announcement',
    {
      data: {
        targetRecordPid: pid,
        clientRequestId: randomUUID(),
        expectedVersion: 2,
        operationType: 'UPDATE',
        payload: { status: 'published' },
      },
    },
  );
  expect(injected.status(), await injected.text()).toBe(422);
  const bypass = await request.put(`/api/dynamic/ab_announcement/${pid}`, {
    data: { title: 'Generic bypass' },
  });
  expect(bypass.status(), await bypass.text()).toBe(400);
  const publish = await request.post('/api/meta/commands/execute/announcement:publish', {
    data: { targetRecordPid: pid, clientRequestId: randomUUID(), expectedVersion: 2, payload: {} },
  });
  expect(publish.status(), await publish.text()).toBe(422);
  const db = new Client(PG_CONN);
  await db.connect();
  try {
    const rows = await db.query(
      'SELECT title,status,row_version,active_version_pid FROM ab_announcement WHERE pid=$1',
      [pid],
    );
    expect(rows.rows).toEqual([
      { title: 'Saved revision', status: 'draft', row_version: 2, active_version_pid: null },
    ]);
    const audit = await db.query(
      'SELECT command_code,resulting_revision FROM ab_announcement_audit WHERE announcement_pid=$1 ORDER BY resulting_revision',
      [pid],
    );
    expect(audit.rows).toEqual([
      { command_code: 'announcement:create_announcement', resulting_revision: 1 },
      { command_code: 'announcement:update_announcement', resulting_revision: 2 },
    ]);
    expect(
      (
        await db.query(
          'SELECT count(*)::int AS n FROM ab_announcement_version WHERE announcement_pid=$1',
          [pid],
        )
      ).rows[0].n,
    ).toBe(0);
  } finally {
    await db.end();
  }
  const readerList = await request.get('/api/announcements?limit=100');
  expect(readerList.status()).toBe(200);
  expect((await readerList.json()).data.some((row: { pid: string }) => row.pid === pid)).toBe(
    false,
  );

  const remove = {
    targetRecordPid: pid,
    clientRequestId: randomUUID(),
    expectedVersion: 2,
    operationType: 'DELETE',
    payload: {},
  };
  const deleted = await request.post(
    '/api/meta/commands/execute/announcement:delete_announcement',
    { data: remove },
  );
  expect(deleted.status(), await deleted.text()).toBe(200);
  const deletedReplay = await request.post(
    '/api/meta/commands/execute/announcement:delete_announcement',
    { data: remove },
  );
  expect(deletedReplay.status(), await deletedReplay.text()).toBe(200);
  expect((await deletedReplay.json()).data.data).toEqual((await deleted.json()).data.data);
});

test('concurrent edits admit one observed revision and preserve the winner', async ({
  request,
}) => {
  const created = await request.post(
    '/api/meta/commands/execute/announcement:create_announcement',
    {
      data: {
        clientRequestId: randomUUID(),
        operationType: 'CREATE',
        payload: { title: 'Concurrent draft' },
      },
    },
  );
  expect(created.status(), await created.text()).toBe(200);
  const pid = (await created.json()).data.data.pid;
  const edits = await Promise.all(
    ['Editor A', 'Editor B'].map((title) =>
      request.post('/api/meta/commands/execute/announcement:update_announcement', {
        data: {
          targetRecordPid: pid,
          expectedVersion: 1,
          clientRequestId: randomUUID(),
          operationType: 'UPDATE',
          payload: { title },
        },
      }),
    ),
  );
  expect(edits.map((response) => response.status()).sort()).toEqual([200, 409]);
  const winner = edits[0].status() === 200 ? 'Editor A' : 'Editor B';
  const loaded = await request.get(`/api/dynamic/ab_announcement/${pid}`);
  expect(loaded.status()).toBe(200);
  expect((await loaded.json()).data).toMatchObject({ title: winner, row_version: 2 });
});

test('REST compatibility writes delegate to the same operation and revision contract', async ({
  request,
}) => {
  const create = await request.post('/api/announcements', {
    data: { operationId: randomUUID(), title: 'REST draft', priority: 'important' },
  });
  expect(create.status(), await create.text()).toBe(200);
  const pid = (await create.json()).data.pid;
  const update = await request.put(`/api/announcements/${pid}`, {
    data: { operationId: randomUUID(), expectedRevision: 1, title: 'REST saved' },
  });
  expect(update.status(), await update.text()).toBe(200);
  expect((await update.json()).data.row_version).toBe(2);
  const stale = await request.put(`/api/announcements/${pid}`, {
    data: { operationId: randomUUID(), expectedRevision: 1, title: 'REST stale' },
  });
  expect(stale.status(), await stale.text()).toBe(409);
  const headers = { 'Idempotency-Key': randomUUID(), 'If-Match': '2' };
  const remove = await request.delete(`/api/announcements/${pid}`, { headers });
  expect(remove.status(), await remove.text()).toBe(200);
  expect((await remove.json()).data.status).toBe('deleted');
});
