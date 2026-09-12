/** Public conversation APIs enforce ownership even for another tenant administrator. */
import { test, expect, request as requestFactory, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
test('new analysis conversations remain private for reads and forged message writes', async ({
  request,
}) => {
  const db = new Client(PG_CONN);
  await db.connect();
  const create = async (client: APIRequestContext) => {
    const response = await client.post('/api/ai/aurabot/conversations', {
      data: { newConversation: true },
    });
    expect(response.status(), await response.text()).toBe(200);
    return (await response.json()).data.conversationId;
  };
  const send = async (client: APIRequestContext, conversationId: number, message: string) =>
    client.post('/api/ai/aurabot/chat/stream', {
      data: {
        conversationId,
        sessionId: randomUUID(),
        clientMsgId: randomUUID(),
        message,
        options: { provider: 'stub', model: 'stub-model' },
      },
    });
  const messages = async (id: number) =>
    (
      await db.query('SELECT id, content FROM ab_im_message WHERE conversation_id=$1 ORDER BY id', [
        id,
      ])
    ).rows;
  let other: APIRequestContext | undefined;
  try {
    const ownerConversation = await create(request);
    const ownerText = `Private analysis ${randomUUID()}`;
    const sent = await send(request, ownerConversation, ownerText);
    expect(sent.status()).toBe(200);
    expect(await sent.text()).toContain('event:done');
    const before = await messages(ownerConversation);
    expect(before.length).toBeGreaterThanOrEqual(2);
    expect(before.some((row) => row.content === ownerText)).toBe(true);
    const owner = (
      await db.query('SELECT tenant_id, owner_id FROM ab_im_conversation WHERE id=$1', [
        ownerConversation,
      ])
    ).rows[0];
    const email = `conversation-${randomUUID()}@e2e.local`,
      password = `Aa7!${randomUUID()}`;
    const user = await request.post('/api/admin/users', {
      data: {
        email,
        displayName: 'Other conversation administrator',
        initialPassword: password,
        roleCodes: ['tenant_admin'],
        sendInviteEmail: false,
      },
    });
    expect(user.status(), await user.text()).toBe(200);
    const login = await request.post('/api/auth/login', { data: { email, password } });
    expect(login.status()).toBe(200);
    const jwt = (await login.json()).data.jwt;
    const raw = Buffer.from(jwt.split('.')[1], 'base64url').toString('utf8');
    expect(raw.match(/"tenantId"\s*:\s*"?(\d+)/)?.[1]).toBe(String(owner.tenant_id));
    other = await requestFactory.newContext({
      baseURL: process.env.BACKEND_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${jwt}` },
    });
    const foreignConversation = await create(other);
    expect(foreignConversation).not.toBe(ownerConversation);
    const foreignOwner = (
      await db.query('SELECT tenant_id, owner_id FROM ab_im_conversation WHERE id=$1', [
        foreignConversation,
      ])
    ).rows[0];
    expect(foreignOwner.tenant_id).toBe(owner.tenant_id);
    expect(foreignOwner.owner_id).not.toBe(owner.owner_id);
    const ownSent = await send(other, foreignConversation, 'My independent analysis');
    expect(ownSent.status()).toBe(200);
    expect(await ownSent.text()).toContain('event:done');
    for (const [client, forbiddenId, allowedId] of [
      [other, ownerConversation, foreignConversation],
      [request, foreignConversation, ownerConversation],
    ] as const) {
      const list = await client.get('/api/ai/aurabot/conversations');
      expect(list.status()).toBe(200);
      const ids = (await list.json()).data.map(
        (row: { conversationId: number }) => row.conversationId,
      );
      expect(ids).toContain(allowedId);
      expect(ids).not.toContain(forbiddenId);
      const denied = await client.get(`/api/ai/aurabot/conversations/${forbiddenId}/messages`);
      expect(denied.status()).toBe(403);
      expect(await denied.text()).toContain('Not a member');
      const allowed = await client.get(`/api/ai/aurabot/conversations/${allowedId}/messages`);
      expect(allowed.status()).toBe(200);
      expect((await allowed.json()).data.length).toBeGreaterThanOrEqual(2);
      const preserved = await messages(forbiddenId);
      const forged = await send(client, forbiddenId, `Unauthorized write ${randomUUID()}`);
      const body = await forged.text();
      expect([200, 400, 403]).toContain(forged.status());
      if (forged.status() === 200) {
        expect(body).toContain('event:error');
        expect(body).not.toContain('event:done');
      }
      expect(await messages(forbiddenId)).toEqual(preserved);
    }
    expect(await messages(ownerConversation)).toEqual(before);
  } finally {
    await other?.dispose();
    await db.end();
  }
});
