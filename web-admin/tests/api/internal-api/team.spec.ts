import { test, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
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
const catalog = JSON.parse(
  await fs.readFile(
    new URL('../../../../scripts/internal-api/catalog.json', import.meta.url),
    'utf8',
  ),
);
const validate = createResponseShapeValidator(document);

test('generated team operations preserve owned memberships and nullable response fields', async ({
  browser,
  baseURL,
}, info) => {
  test.setTimeout(120_000);
  if (!baseURL) throw new Error('A managed runtime is required');
  const actor = await provisionInternalApiUser(browser, baseURL);
  const anonymous = await browser.newContext({
    baseURL,
    storageState: { cookies: [], origins: [] },
  });
  const code = `ic_team_${randomUUID().replaceAll('-', '').slice(0, 16)}`;
  const observed = new Set<string>(),
    rows: object[] = [],
    cleanupErrors: unknown[] = [];
  let teamPid: string | undefined, memberPid: string | undefined, journeyError: unknown;
  async function call(method: string, path: string, url: string, body?: unknown) {
    const response = await actor.context.request.fetch(url, {
      method,
      headers: { Origin: actor.origin },
      ...(body === undefined ? {} : { data: body }),
    });
    const payload = await response.json();
    if (method === 'post' && path === '/api/org/teams' && typeof payload.data?.pid === 'string')
      teamPid = payload.data.pid;
    const shape = validate({
      method,
      path,
      status: response.status(),
      contentType: response.headers()['content-type'],
      payload,
    });
    rows.push({
      method,
      path,
      status: response.status(),
      shape,
      payloadSha256: createHash('sha256').update(JSON.stringify(payload)).digest('hex'),
    });
    expect(response.status()).toBe(200);
    expect(shape.errors).toEqual([]);
    expect(payload.code).toBe('0');
    observed.add(`${method} ${path}`);
    return payload.data;
  }
  try {
    const created = await call('post', '/api/org/teams', '/api/org/teams', { code, name: code });
    expect(created.description).toBeNull();
    expect(created.leaderId).toBeNull();
    expect(created.leaderName).toBeNull();
    expect(
      (await call('get', '/api/org/teams', '/api/org/teams')).some(
        (t: { pid: string }) => t.pid === teamPid,
      ),
    ).toBe(true);
    expect((await call('get', '/api/org/teams/{pid}', `/api/org/teams/${teamPid}`)).pid).toBe(
      teamPid,
    );
    expect(
      (
        await call('put', '/api/org/teams/{pid}', `/api/org/teams/${teamPid}`, {
          name: `${code} updated`,
        })
      ).name,
    ).toBe(`${code} updated`);
    expect(
      await call('get', '/api/org/teams/{teamPid}/members', `/api/org/teams/${teamPid}/members`),
    ).toEqual([]);
    const member = await call(
      'post',
      '/api/org/teams/{teamPid}/members',
      `/api/org/teams/${teamPid}/members`,
      { userPid: actor.userPid },
    );
    memberPid = member.pid;
    expect(typeof member.userId).toBe('number');
    expect(member.userPid).toBe(actor.userPid);
    expect(member.userEmail).toBeNull();
    expect(typeof member.memberPid).toBe('string');
    expect(member.memberPid).not.toBe(memberPid);
    expect(
      (
        await call('get', '/api/org/teams/{teamPid}/members', `/api/org/teams/${teamPid}/members`)
      ).map((m: { pid: string }) => m.pid),
    ).toEqual([memberPid]);
    expect(
      (await call('get', '/api/org/teams/current-user', '/api/org/teams/current-user')).some(
        (t: { pid: string }) => t.pid === teamPid,
      ),
    ).toBe(true);
    await call(
      'delete',
      '/api/org/teams/{teamPid}/members/{memberPid}',
      `/api/org/teams/${teamPid}/members/${memberPid}`,
    );
    expect(
      await call('get', '/api/org/teams/{teamPid}/members', `/api/org/teams/${teamPid}/members`),
    ).toEqual([]);
    expect(
      (await call('get', '/api/org/teams/current-user', '/api/org/teams/current-user')).some(
        (t: { pid: string }) => t.pid === teamPid,
      ),
    ).toBe(false);
    const expected = catalog.profiles.teamsWire.operations
      .map((op: { method: string; path: string }) => `${op.method} ${op.path}`)
      .sort();
    // The delete-team operation is checked after the anonymous boundary probes.
    for (const operation of catalog.profiles.teamsWire.operations) {
      const url = operation.path
        .replaceAll('{teamPid}', teamPid!)
        .replaceAll('{pid}', teamPid!)
        .replaceAll('{memberPid}', memberPid!);
      const denied = await anonymous.request.fetch(url, {
        method: operation.method,
        headers: { Origin: actor.origin },
        ...(['post', 'put'].includes(operation.method)
          ? { data: { name: code, code, userPid: actor.userPid } }
          : {}),
      });
      expect(denied.status(), `Anonymous ${operation.method} ${operation.path}`).toBe(401);
    }
    await call('delete', '/api/org/teams/{pid}', `/api/org/teams/${teamPid}`);
    expect([...observed].sort()).toEqual(expected);
    expect(
      (await call('get', '/api/org/teams', '/api/org/teams')).some(
        (t: { pid: string }) => t.pid === teamPid,
      ),
    ).toBe(false);
  } catch (error) {
    journeyError = error;
  } finally {
    if (teamPid) {
      try {
        const db = new pg.Client(PG_CONN);
        await db.connect();
        try {
          const row = await db.query('SELECT deleted_flag FROM ab_team WHERE pid=$1 AND code=$2', [
            teamPid,
            code,
          ]);
          expect(row.rowCount).toBe(1);
          if (!row.rows[0].deleted_flag) {
            const response = await actor.context.request.delete(`/api/org/teams/${teamPid}`, {
              headers: { Origin: actor.origin },
            });
            expect(response.status()).toBe(200);
            expect((await response.json()).code).toBe('0');
          }
          expect(
            (
              await db.query('SELECT deleted_flag FROM ab_team WHERE pid=$1 AND code=$2', [
                teamPid,
                code,
              ])
            ).rows[0].deleted_flag,
          ).toBe(true);
        } finally {
          await db.end();
        }
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    try {
      await anonymous.close();
      await actor.dispose();
    } catch (error) {
      cleanupErrors.push(error);
    }
    await info.attach('team-wire-observations', {
      body: JSON.stringify({
        claim:
          'listed-team-json-structure-and-owned-membership-invariants; no tenant/role or format acceptance',
        rows,
        journeyError: journeyError ? String(journeyError) : null,
        cleanupErrors: cleanupErrors.map(String),
      }),
      contentType: 'application/json',
    });
  }
  if (journeyError || cleanupErrors.length)
    throw new AggregateError(
      [...(journeyError ? [journeyError] : []), ...cleanupErrors],
      'Team wire journey or cleanup failed',
    );
});
