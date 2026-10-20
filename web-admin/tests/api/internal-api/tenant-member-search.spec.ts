import { test, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { provisionInternalApiUser } from '../../helpers/internal-api-owned-user';
import { createResponseShapeValidator } from '../../../../scripts/internal-api/response-shape.mjs';
const document = JSON.parse(
  await fs.readFile(
    new URL('../../../../contracts/internal-web-admin/openapi.json', import.meta.url),
    'utf8',
  ),
);
const validate = createResponseShapeValidator(document);

test('generated tenant member search preserves scoped public identities and email-only null usernames', async ({
  browser,
  baseURL,
}, info) => {
  test.setTimeout(120_000);
  if (!baseURL) throw new Error('A managed runtime is required');
  const owner = await provisionInternalApiUser(browser, baseURL);
  let reader: Awaited<ReturnType<typeof provisionInternalApiUser>> | undefined;
  const email = `ic_member_${randomUUID().replaceAll('-', '')}@example.invalid`;
  let userPid: string | undefined, journeyError: unknown;
  const rows: object[] = [],
    cleanupErrors: unknown[] = [];
  const anonymous = await browser.newContext({
    baseURL,
    storageState: { cookies: [], origins: [] },
  });
  try {
    const created = await owner.context.request.post('/api/admin/users', {
      headers: { Origin: owner.origin },
      data: {
        email,
        displayName: 'Owned email-only member',
        initialPassword: `Aa7!${randomUUID()}`,
        roleCodes: [],
        roleAssignmentMode: 'NONE',
        sendInviteEmail: false,
      },
    });
    const provision = await created.json();
    if (typeof provision.data?.userPid === 'string') userPid = provision.data.userPid;
    expect(created.status()).toBe(200);
    expect(provision.code).toBe('0');
    expect(userPid).toBeTruthy();
    reader = await provisionInternalApiUser(browser, baseURL, null);
    for (const actor of [owner, reader]) {
      const response = await actor.context.request.post('/api/tenant/members/search', {
        headers: { Origin: actor.origin },
        data: { keyword: email, pageNum: 1, pageSize: 20, status: 'active' },
      });
      const payload = await response.json();
      const shape = validate({
        method: 'post',
        path: '/api/tenant/members/search',
        status: response.status(),
        contentType: response.headers()['content-type'],
        payload,
      });
      rows.push({ role: actor.role, status: response.status(), shape });
      expect(response.status()).toBe(200);
      expect(payload.code).toBe('0');
      expect(shape.errors).toEqual([]);
      expect(payload.data.records).toHaveLength(1);
      const member = payload.data.records[0];
      expect(member.pid).toBe(provision.data.memberPid);
      expect(member.user.pid).toBe(userPid);
      expect(member.pid).not.toBe(member.user.pid);
      expect(member.user.username).toBeNull();
      expect(member.user.email).toBe(email);
      expect(Object.keys(member).sort()).toEqual(['pid', 'status', 'user']);
      expect(member).not.toHaveProperty('userId');
    }
    const denied = await anonymous.request.post('/api/tenant/members/search', {
      data: { keyword: email },
    });
    expect(denied.status()).toBe(401);
  } catch (error) {
    journeyError = error;
  } finally {
    if (userPid) {
      const db = new pg.Client(PG_CONN);
      try {
        await db.connect();
        const changed = await db.query(
          'UPDATE ab_user SET is_enabled=false, deleted_flag=true, security_version=COALESCE(security_version,0)+1 WHERE pid=$1 AND email=$2 RETURNING id',
          [userPid, email],
        );
        expect(changed.rowCount).toBe(1);
        await db.query('UPDATE ab_tenant_member SET deleted_flag=true WHERE user_id=$1', [
          changed.rows[0].id,
        ]);
        const remaining = await db.query(
          'SELECT count(*)::int AS count FROM ab_tenant_member WHERE user_id=$1 AND deleted_flag=false',
          [changed.rows[0].id],
        );
        expect(remaining.rows[0].count).toBe(0);
      } catch (error) {
        cleanupErrors.push(error);
      } finally {
        await db.end();
      }
    }
    for (const actor of [reader, owner])
      if (actor) {
        try {
          await actor.dispose();
        } catch (error) {
          cleanupErrors.push(error);
        }
      }
    await anonymous.close();
    await info.attach('tenant-member-search-observations', {
      body: JSON.stringify({
        rows,
        journeyError: journeyError ? String(journeyError) : null,
        cleanupErrors: cleanupErrors.map(String),
        sessionCleanup: [reader, owner].filter(Boolean).map((actor) => actor!.sessionCleanup),
      }),
      contentType: 'application/json',
    });
  }
  if (journeyError || cleanupErrors.length)
    throw new AggregateError(
      [...(journeyError ? [journeyError] : []), ...cleanupErrors],
      'Member search journey or cleanup failed',
    );
});
