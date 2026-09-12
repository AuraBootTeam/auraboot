/** Real conversation dispatch and persisted execution facts; external LLM uses the configured stub. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('explicit durable conversation commits started and linked successful execution facts', async ({
  request,
}) => {
  const from = new Date().toISOString();
  const marker = `execution-facts-${randomUUID()}`;
  const response = await request.post('/api/ai/aurabot/chat/stream', {
    headers: { Accept: 'text/event-stream' },
    data: {
      sessionId: marker,
      clientMsgId: randomUUID(),
      message: marker,
      options: { explicitDurableRequest: true, durableWorkflow: true },
    },
  });
  expect(response.status()).toBe(200);
  const body = await response.text();
  expect(body).toContain('event:done');
  expect(body).not.toContain('event:error');
  const db = new Client(PG_CONN);
  await db.connect();
  try {
    const runs = await db.query(
      `SELECT r.pid, r.tenant_id, r.run_status, r.actor_user_id, t.task_status
       FROM ab_agent_run r JOIN ab_agent_task t ON t.pid=r.task_id AND t.tenant_id=r.tenant_id
       WHERE t.description=$1`,
      [marker],
    );
    expect(runs.rows).toHaveLength(1);
    const run = runs.rows[0];
    expect(run.run_status).toBe('success');
    expect(run.task_status).toBe('done');
    const events = await db.query(
      `SELECT event_id, event_name, caused_by_event_id, user_id, payload, status
       FROM ab_behavior_outcome_outbox WHERE tenant_id=$1 AND run_id=$2 ORDER BY id`,
      [run.tenant_id, run.pid],
    );
    expect(events.rows.map((row) => row.event_name)).toEqual([
      'agent_execution_started',
      'agent_execution_completed',
    ]);
    expect(events.rows[1].caused_by_event_id).toBe(events.rows[0].event_id);
    expect(events.rows[1].payload.status).toBe('success');
    for (const row of events.rows) expect(row.user_id).toBe(run.actor_user_id);
    await expect
      .poll(
        async () => {
          const result = await db.query(
            `SELECT event_name FROM ab_behavior_event WHERE tenant_id=$1 AND run_id=$2 ORDER BY occurred_at, id`,
            [run.tenant_id, run.pid],
          );
          return result.rows.map((row) => row.event_name).sort();
        },
        { timeout: 15000 },
      )
      .toEqual(['agent_execution_completed', 'agent_execution_started']);
    const stats = await request.get('/api/analytics/behavior/executions', {
      params: { from, to: new Date().toISOString() },
    });
    expect(stats.status()).toBe(200);
    const execution = (await stats.json()).data;
    expect(execution.definitionVersion).toBe('agent-execution-cohort-v1');
    expect(execution.counts).toMatchObject({
      started: 1,
      succeeded: 1,
      failed: 0,
      cancelled: 0,
      unresolved: 0,
      excludedSandbox: 0,
    });
    expect(execution.successRate).toBe(1);
    expect(execution.completedSuccessRate).toBe(1);
    const empty = await request.get('/api/analytics/behavior/executions', {
      params: {
        from: new Date(Date.now() + 60000).toISOString(),
        to: new Date(Date.now() + 120000).toISOString(),
      },
    });
    expect(empty.status()).toBe(200);
    expect((await empty.json()).data).toMatchObject({
      sampleStatus: 'no_sample',
      successRate: null,
      completedSuccessRate: null,
    });
  } finally {
    await db.end();
  }
});
