import { test, expect, type Page } from '@playwright/test';
import { BASE_URL } from '../../helpers/environments';
import {
  makeQuoteRoleUser,
  ensureQuoteRoleUser,
  seedQuoteScaffold,
  reassignRecordOwnerByEmail,
  openPgClient,
} from './quote-e2e-helpers';
const BASE = BASE_URL;

async function createRole(page: Page) {
  const code = `e2e_capsave_${Date.now()}`;
  const resp = await page.request.post(`${BASE}/api/roles`, {
    data: {
      code,
      name: `CapSave ${Date.now()}`,
      description: 'capability save ui golden',
      type: 'custom',
    },
  });
  expect(resp.ok()).toBeTruthy();
  return (await resp.json()).data as { pid: string; code: string };
}

// Reuses the existing role editor and Quote/BOM golden fixtures and release runner. it does not introduce a parallel runner or suite.
test.describe('BOM & Quote domain levels', () => {
  test.use({ storageState: { cookies: [], origins: [] }, locale: 'zh-CN' });
  for (const domain of ['bom', 'qo'] as const) {
    test(`${domain}: domain levels save, revoke and retain data policies`, async ({
      page,
      browser,
    }, info) => {
      test.setTimeout(180_000);
      const login = await page.request.post('/login', {
        form: {
          identifier: process.env.AURA_ADMIN_EMAIL || 'admin@auraboot.com',
          password: process.env.AURA_ADMIN_PASSWORD || 'Test2026x',
          remember: 'on',
          redirectTo: '/home',
        },
        maxRedirects: 0,
      });
      expect(login.headers()['set-cookie']).toContain('__session=');
      const unwrap = async (response: any) => {
        const body = await response.json();
        expect(response.status(), JSON.stringify(body)).toBe(200);
        expect(String(body.code), JSON.stringify(body)).toBe('0');
        return body.data;
      };
      const role = await createRole(page);
      const draftUrl = `/api/permission/roles/${role.pid}/draft`;
      await unwrap(
        await page.request.put(`/api/permissions/matrix/${role.pid}/default-scope`, {
          data: { scopeType: 'self' },
        }),
      );
      await unwrap(
        await page.request.put(`/api/permission/capabilities?rolePid=${role.pid}`, {
          data: [
            `${domain}.cap.read`,
            `${domain}.cap.write`,
            ...(domain === 'qo' ? ['qo.cap.cost'] : []),
          ],
        }),
      );
      const model = domain === 'bom' ? 'req_requirement_set_pcba_bom' : 'qo_quote_common';
      const hiddenField = domain === 'bom' ? 'bom_project_remark' : 'qo_quote_notes';
      await unwrap(
        await page.request.put(`/api/permissions/matrix/${role.pid}/scope`, {
          data: { resourceCode: model, actionCode: 'read', scopeType: 'self' },
        }),
      );
      const initial = await unwrap(await page.request.get(draftUrl));
      await unwrap(
        await page.request.put(draftUrl, {
          data: {
            expectedRevision: initial.revision,
            selectedCapabilityCodes: [
              `${domain}.cap.read`,
              `${domain}.cap.write`,
              ...(domain === 'qo' ? ['qo.cap.cost'] : []),
            ],
            revokedPartialCapabilityCodes: [],
            defaultScopeChanged: false,
            applyDefaultToCurrentGrants: false,
            scopeChanges: [],
            fieldChanges: [{ modelCode: model, fieldCode: hiddenField, access: 'hidden' }],
          },
        }),
      );
      const policyState = (snapshot: any) => ({
        defaultScopeType: snapshot.defaultScopeType,
        fieldPolicies: snapshot.fieldPolicies,
        fieldAccess: snapshot.fieldAccess.flatMap((model: any) =>
          model.fields
            .filter(
              (field: any) => field.access !== 'inherit' || field.additionalRestrictions.length > 0,
            )
            .map((field: any) => ({ model: model.code, ...field })),
        ),
        navigation: {
          mode: snapshot.navigation.mode,
          selectedMenuPids: snapshot.navigation.selectedMenuPids,
        },
      });
      // Retired operations must be absent after the explicit release-data upgrade.
      const upgradedSnapshot = await unwrap(await page.request.get(draftUrl));
      const retiredCodes = domain === 'bom'
        ? ['bom.structure.release', 'bom.project.manage']
        : ['qo.quote.approve', 'qo.quote.manage'];
      const registeredCodes = upgradedSnapshot.matrix.modules
        .flatMap((m: any) => m.resources)
        .flatMap((resource: any) => resource.actions)
        .map((action: any) => action.code);
      for (const code of retiredCodes) expect(registeredCodes).not.toContain(code);
      const baselineSnapshot = await unwrap(await page.request.get(draftUrl));
      const baselineScopes = baselineSnapshot.scopes;
      const baseline = policyState(baselineSnapshot);
      expect(baselineScopes.length).toBeGreaterThan(0);
      expect(baseline.defaultScopeType).toBe('self');
      expect(
        baselineSnapshot.fieldAccess
          .find((entry: any) => entry.code === model)
          .fields.find((field: any) => field.code === hiddenField).access,
      ).toBe('hidden');
      const catalog = (await unwrap(await page.request.get(draftUrl))).groups.flatMap(
        (g: any) => g.capabilities,
      );
      expect(
        catalog
          .filter((cap: any) => cap.code.startsWith(`${domain}.cap.`) && cap.tier !== 'data')
          .map((cap: any) => cap.code)
          .sort(),
      ).toEqual(['manage', 'read', 'write'].map((level) => `${domain}.cap.${level}`).sort());
      const user = makeQuoteRoleUser(`level_${domain}`, `${Date.now()}`, [role.code]);
      await ensureQuoteRoleUser(page, user);
      const actor = await browser.newContext({
        baseURL: BASE,
        locale: 'zh-CN',
        storageState: { cookies: [], origins: [] },
      });
      const actorLogin = await actor.request.post('/login', {
        form: {
          identifier: user.email,
          password: user.password,
          remember: 'on',
          redirectTo: '/home',
        },
        maxRedirects: 0,
      });
      expect(actorLogin.headers()['set-cookie']).toContain('__session=');
      const command = (
        code: string,
        payload: Record<string, unknown> = {},
        targetRecordPid?: string,
      ) =>
        actor.request.post(`/api/meta/commands/execute/${encodeURIComponent(code)}`, {
          data: {
            payload,
            targetRecordPid,
            clientRequestId: `levels-${domain}-${Date.now()}-${Math.random()}`,
          },
        });
      const normal = domain === 'bom' ? 'bom:create_project' : 'qo_quote_common:update';
      const release =
        domain === 'bom' ? 'bom:release_structure_revision' : 'qo_quote_common:approve';
      const fixture =
        domain === 'bom'
          ? { bom_project_name: `LEVEL-${Date.now()}`, bom_project_quality_level: 'industrial' }
          : { qo_quote_discount_pct: 0.05 };
      let createdPid: string | undefined;
      let foreignQuotePid: string | undefined;
      const setHiddenFixture = async (recordPid: string) => {
        const { client, database } = await openPgClient();
        try {
          expect(database).toBe(process.env.POSTGRES_DB);
          const seeded = await client.query(
            `UPDATE mt_${model} SET ${hiddenField}=$1 WHERE pid=$2`,
            ['Domain-level hidden fixture', recordPid],
          );
          expect(seeded.rowCount).toBe(1);
        } finally {
          await client.end();
        }
      };
      if (domain === 'qo') {
        await page.goto('/home');
        // Reuse the release gate's existing scaffold and SELF-owner fixture helpers.
        const seeded = await seedQuoteScaffold(page, 'DOMAIN-LEVEL', []);
        await reassignRecordOwnerByEmail(seeded.rows, user.email);
        createdPid = seeded.quoteId;
        await setHiddenFixture(createdPid);
        // A separate admin-owned fixture proves domain manage does not widen SELF.
        foreignQuotePid = (await seedQuoteScaffold(page, 'DOMAIN-FOREIGN', [])).quoteId;
      }
      try {
        await page.goto('/enterprise/permissions');
        await expect(page.getByTestId('role-table')).toBeVisible();
        await page.getByTestId('role-search-input').fill(role.code);
        await page.getByTestId(`role-item-${role.code}`).click();
        await page.getByTestId('permission-right-tab-capabilities').click();
        // Removed operation atoms must not appear as legacy operation authority.
        await expect(page.getByTestId(`domain-legacy-${domain}.cap.read`)).toHaveCount(0);
        await expect(
          page.getByTestId(`domain-legacy-${domain === 'bom' ? 'qo' : 'bom'}.cap.read`),
        ).toHaveCount(0);
        const domainCard = page.getByTestId(
          `capability-group-${domain === 'bom' ? 'BOM' : '报价（Quote）'}`,
        );
        await expect(domainCard.locator('input[type="radio"]')).toHaveCount(4);
        await expect(domainCard.locator('input[type="checkbox"]')).toHaveCount(0);
        for (const [iteration, level] of [
          'read',
          'write',
          'manage',
          'write',
          'read',
          'none',
        ].entries()) {
          const selector =
            level === 'none'
              ? `domain-level-none-${domain}.cap.read`
              : `domain-level-${domain}.cap.${level}`;
          await page.getByTestId(selector).scrollIntoViewIfNeeded();
          await page.getByTestId(selector).check();
          const preview = page.waitForResponse(
            (r) => r.url().includes('/draft/preview') && r.request().method() === 'POST',
          );
          await page.getByTestId('capability-save').click();
          const reviewed = await unwrap(await preview);
          expect(reviewed.defaultScopeChanged).toBe(false);
          expect(reviewed.scopeChanges).toHaveLength(0);
          await expect(page.getByTestId('confirm-dialog')).toBeVisible();
          await page.screenshot({
            path: info.outputPath(`${domain}-${iteration}-${level}-preview.png`),
            fullPage: true,
          });
          const saved = page.waitForResponse(
            (r) => new URL(r.url()).pathname === draftUrl && r.request().method() === 'PUT',
          );
          await page.getByTestId('confirm-ok').click();
          await unwrap(await saved);
          await expect(page.getByTestId('confirm-dialog')).toBeHidden();
          const snapshot = await unwrap(await page.request.get(draftUrl));
          if (domain === 'qo')
            expect(
              snapshot.groups
                .flatMap((g: any) => g.capabilities)
                .find((c: any) => c.code === 'qo.cap.cost').granted,
            ).toBe(true);
          if (domain === 'qo')
            expect(
              snapshot.groups
                .flatMap((g: any) => g.capabilities)
                .find((c: any) => c.code === 'qo.cap.evidence').granted,
            ).toBe(false);
          expect(policyState(snapshot)).toEqual(baseline);
          expect(snapshot.scopes).toEqual(expect.arrayContaining(baselineScopes));
          // New actions may inherit the existing SELF default; original ranges must survive.
          for (const scope of snapshot.scopes) expect(scope.scopeType).toBe('self');
          const granted = snapshot.groups
            .flatMap((g: any) => g.capabilities)
            .filter((c: any) => c.granted)
            .map((c: any) => c.code);
          const expected =
            level === 'none'
              ? []
              : ['read', 'write', 'manage'].slice(
                  0,
                  ['read', 'write', 'manage'].indexOf(level) + 1,
                );
          for (const name of ['read', 'write', 'manage'])
            expect(granted.includes(`${domain}.cap.${name}`)).toBe(expected.includes(name));
          const result = await command(normal, fixture, domain === 'qo' ? createdPid : undefined);
          if (level === 'read' || level === 'none') expect(result.status()).toBe(403);
          else {
            const output = await unwrap(result);
            if (domain === 'bom')
              createdPid = (output.data ?? output).recordPid ?? (output.data ?? output).recordId;
            expect(createdPid).toMatch(/^[A-Z0-9]{26}$/);
            if (domain === 'bom') await setHiddenFixture(createdPid!);
          }
          const releaseResponse = await command(
            release,
            {},
            createdPid || '01ARZ3NDEKTSV4RRFFQ69G5FAV',
          );
          if (level === 'manage') {
            expect(releaseResponse.status()).not.toBe(403);
            const body = await releaseResponse.json();
            expect(String(body.code)).not.toBe('0'); // Manage cannot publish an invalid source/state.
          } else expect(releaseResponse.status()).toBe(403);
          if (createdPid && level !== 'none') {
            const record = await unwrap(
              await actor.request.get(`/api/dynamic/${model}/${createdPid}`),
            );
            expect(record[hiddenField] == null).toBe(true);
            if (domain === 'qo' && iteration > 0)
              expect(Number(record.qo_quote_discount_pct)).toBe(0.05);
            const { client } = await openPgClient();
            try {
              const raw = await client.query(
                `SELECT ${hiddenField} AS value FROM mt_${model} WHERE pid=$1`,
                [createdPid],
              );
              expect(raw.rows[0].value).toBe('Domain-level hidden fixture');
            } finally {
              await client.end();
            }
          }
          if (domain === 'qo') {
            for (const query of ['qo_quote_bom_price_metrics', 'qo_quote_process_fee_metrics', 'qo_quote_output_readiness']) {
              const queryUrl = (quoteId: string) => `/api/datasource/list?${new URLSearchParams({
                datasourceId: `nq:${query}`, valueField: 'pid', labelField: 'name', format: 'records', quoteId,
              })}`;
              const own = await actor.request.get(queryUrl(createdPid!));
              if (level === 'none') expect(own.status()).toBe(403);
              else await unwrap(own);
              const foreign = await actor.request.get(queryUrl(foreignQuotePid!));
              expect(foreign.status(), `${query} must preserve SELF even at ${level}`).toBe(403);
            }
          }
          const other = domain === 'bom' ? 'qo_quote_common:create' : 'bom:create_project';
          expect((await command(other, fixture)).status()).toBe(403);
          await page.reload();
          await page.getByTestId('role-search-input').fill(role.code);
          await page.getByTestId(`role-item-${role.code}`).click();
          await page.getByTestId('permission-right-tab-capabilities').click();
          await expect(page.getByTestId(selector)).toBeChecked();
          await expect(
            page.getByTestId(`domain-legacy-${domain === 'bom' ? 'qo' : 'bom'}.cap.read`),
          ).toHaveCount(0);
          await page.getByTestId(selector).scrollIntoViewIfNeeded();
          await page.screenshot({
            path: info.outputPath(`${domain}-${iteration}-${level}-saved.png`),
            fullPage: true,
          });
        }
      } finally {
        await actor.close();
      }
    });
  }
});
