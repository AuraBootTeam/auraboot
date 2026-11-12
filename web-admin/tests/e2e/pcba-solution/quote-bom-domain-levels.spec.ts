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
      const retiredCodes =
        domain === 'bom'
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
            for (const query of [
              'qo_quote_bom_price_metrics',
              'qo_quote_process_fee_metrics',
              'qo_quote_output_readiness',
            ]) {
              const queryUrl = (quoteId: string) =>
                `/api/datasource/list?${new URLSearchParams({
                  datasourceId: `nq:${query}`,
                  valueField: 'pid',
                  labelField: 'name',
                  format: 'records',
                  quoteId,
                })}`;
              const own = await actor.request.get(queryUrl(createdPid!));
              if (level === 'none') expect(own.status()).toBe(403);
              else {
                expect(own.status(), `${query}: own quote must remain readable at ${level}`).toBe(
                  200,
                );
                await unwrap(own);
              }
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

        // Configure finite fields and the next business grant through the same existing role draft.
        const beforeFinite = await unwrap(await page.request.get(draftUrl));
        expect((await actor.request.get(`/api/dynamic/${model}/${createdPid}`)).status()).toBe(403);
        await page.getByTestId(`domain-level-${domain}.cap.write`).check();
        await page.getByTestId('permission-right-tab-scope').click();
        await page.getByTestId('role-field-model').selectOption(model);
        const readFields =
          domain === 'bom'
            ? ['bom_project_name', 'bom_project_quality_level']
            : ['qo_quote_code', 'qo_quote_discount_pct'];
        const writeFields = domain === 'bom' ? readFields : ['qo_quote_discount_pct'];
        for (const level of ['read', 'write']) {
          const grant = page.locator(`[data-testid^="grant-fields-${domain}.cap.${level}-"]`);
          await expect(grant).toHaveCount(1);
          await grant
            .getByRole('button', {
              name: /配置此对象|设置明确字段列表|Configure this object|Set a finite field list/,
            })
            .click();
          const checkedIds = await grant
            .locator('input[type="checkbox"]:checked')
            .evaluateAll((inputs) => inputs.map((input) => input.getAttribute('data-testid')!));
          for (const id of checkedIds) await grant.getByTestId(id).uncheck();
          for (const field of readFields) {
            await grant.locator(`[data-testid$="-read-${field}"]`).check();
          }
          if (level === 'write') {
            for (const field of writeFields) {
              await grant.locator(`[data-testid$="-write-${field}"]`).check();
            }
          }
        }
        await expect(page.getByTestId('grant-fields-pending')).toBeVisible();
        // Candidate previews and unsaved checkbox changes must not grant access.
        expect((await unwrap(await page.request.get(draftUrl))).revision).toBe(
          beforeFinite.revision,
        );
        expect((await actor.request.get(`/api/dynamic/${model}/${createdPid}`)).status()).toBe(403);
        const finitePreview = page.waitForResponse(
          (r) =>
            new URL(r.url()).pathname === `${draftUrl}/preview` &&
            r.request().method() === 'POST' &&
            (r.request().postDataJSON().grantFieldChanges?.length ?? 0) === 2,
        );
        await page.getByTestId('capability-save').click();
        const finiteResponse = await finitePreview;
        const finiteBody = finiteResponse.request().postDataJSON();
        const finiteReview = await unwrap(finiteResponse);
        expect(finiteBody.defaultScopeChanged).toBe(false);
        expect(finiteBody.scopeChanges).toHaveLength(0);
        expect(finiteBody.fieldChanges ?? []).toHaveLength(0);
        expect(finiteBody.grantFieldChanges).toHaveLength(2);
        expect(finiteReview.grantFieldChanges).toHaveLength(2);
        for (const change of finiteBody.grantFieldChanges) {
          expect(change.modelCode).toBe(model);
          expect(change.expectedMetadataRevision).toMatch(/^[a-f0-9]{64}$/);
        }
        await expect(page.getByTestId('draft-preview-grant-fields')).toBeVisible();
        await page.screenshot({
          path: info.outputPath(`${domain}-finite-fields-preview.png`),
          fullPage: true,
        });
        const finiteSave = page.waitForResponse(
          (r) => new URL(r.url()).pathname === draftUrl && r.request().method() === 'PUT',
        );
        await page.getByTestId('confirm-ok').click();
        await unwrap(await finiteSave);
        await expect(page.getByTestId('confirm-dialog')).toBeHidden();
        const finiteSnapshot = await unwrap(await page.request.get(draftUrl));
        expect(policyState(finiteSnapshot)).toEqual(baseline);
        expect(finiteSnapshot.scopes).toEqual(expect.arrayContaining(beforeFinite.scopes));
        for (const level of ['read', 'write']) {
          const bindings = finiteSnapshot.grantFields.filter(
            (binding: any) =>
              binding.permissionCode === `${domain}.cap.${level}` && binding.modelCode === model,
          );
          expect(bindings).toHaveLength(1);
          expect(bindings[0].readFields.slice().sort()).toEqual(readFields.slice().sort());
          expect(bindings[0].writeFields.slice().sort()).toEqual(
            level === 'write' ? writeFields.slice().sort() : [],
          );
        }
        const otherObjects = finiteSnapshot.grantFields.filter(
          (binding: any) =>
            binding.modelCode !== model &&
            [`${domain}.cap.read`, `${domain}.cap.write`].includes(binding.permissionCode),
        );
        expect(otherObjects.length).toBeGreaterThan(0);
        expect(otherObjects.every((binding: any) => binding.legacy === true)).toBe(true);
        // The actor keeps the original session; the newly saved field allowance must drive real execution.
        const allowed = await unwrap(
          await command(normal, fixture, domain === 'qo' ? createdPid : undefined),
        );
        expect.soft(allowed.data ?? allowed).not.toHaveProperty(hiddenField);
        if (domain === 'bom') {
          createdPid = (allowed.data ?? allowed).recordPid ?? (allowed.data ?? allowed).recordId;
          expect(createdPid).toMatch(/^[A-Z0-9]{26}$/);
          await setHiddenFixture(createdPid!);
        }
        const excluded = domain === 'bom' ? 'bom_pcba_code' : 'qo_quote_tax_rate';
        const { client } = await openPgClient();
        try {
          if (domain === 'bom') {
            const seeded = await client.query(
              `UPDATE mt_${model} SET ${excluded}=$1 WHERE pid=$2`,
              ['FINITE-FIELD-SOURCE', createdPid],
            );
            expect(seeded.rowCount).toBe(1);
          }
          const beforeDenied = (
            await client.query(`SELECT * FROM mt_${model} WHERE pid=$1`, [createdPid])
          ).rows;
          expect(beforeDenied).toHaveLength(1);
          expect(beforeDenied[0][excluded]).not.toBeNull();
          const projected = await unwrap(
            await actor.request.get(`/api/dynamic/${model}/${createdPid}`),
          );
          for (const field of readFields) expect(projected).toHaveProperty(field);
          expect(projected).not.toHaveProperty(excluded);
          expect(projected).not.toHaveProperty(hiddenField);
          const rejectedName = `FINITE-DENIED-${Date.now()}`;
          const deniedPayload =
            domain === 'bom'
              ? { ...fixture, bom_project_name: rejectedName, [excluded]: 'DISALLOWED' }
              : { qo_quote_discount_pct: 0.07, [excluded]: 0.2 };
          const denied = await command(
            normal,
            deniedPayload,
            domain === 'qo' ? createdPid : undefined,
          );
          const deniedBody = await denied.json();
          expect.soft(denied.status()).toBe(403);
          const afterDenied = (
            await client.query(`SELECT * FROM mt_${model} WHERE pid=$1`, [createdPid])
          ).rows;
          const deniedWritePreservedRow =
            JSON.stringify(afterDenied) === JSON.stringify(beforeDenied);
          expect.soft(afterDenied).toEqual(beforeDenied);
          if (domain === 'bom') {
            expect(
              (
                await client.query(
                  `SELECT count(*)::int AS count FROM mt_${model} WHERE bom_project_name=$1`,
                  [rejectedName],
                )
              ).rows[0].count,
            ).toBe(0);
          }
          if (domain === 'qo') {
            expect(
              (await actor.request.get(`/api/dynamic/${model}/${foreignQuotePid}`)).status(),
            ).toBe(403);
            const queuedBefore = await client.query(
              `SELECT task_code FROM ab_async_task WHERE input_params->>'recordPid'=$1 ORDER BY id`,
              [createdPid],
            );
            const beforeAsync = (
              await client.query(`SELECT * FROM mt_${model} WHERE pid=$1`, [createdPid])
            ).rows;
            const asyncDenied = await command(
              'qo_quote_common:recompute_quantities',
              { qo_quote_set_count: 7, qo_quote_price_factor: 1.37 },
              createdPid,
            );
            const asyncBody = await asyncDenied.json();
            expect(asyncDenied.status()).toBe(200);
            expect(String(asyncBody.code)).toBe('0');
            const submitted = asyncBody.data?.data ?? asyncBody.data;
            expect(submitted.async).toBe(true);
            expect(submitted.taskCode).toMatch(/^[A-Z0-9]{26}$/);
            await expect
              .poll(
                async () =>
                  (
                    await client.query(
                      `SELECT status FROM ab_async_task WHERE tenant_id=$1 AND task_code=$2`,
                      [beforeAsync[0].tenant_id, submitted.taskCode],
                    )
                  ).rows[0]?.status,
                {
                  timeout: 30_000,
                  message: 'Denied async input must reach an explicit failed task',
                },
              )
              .toBe('failed');
            const delivered = (
              await client.query(
                `SELECT task_code,status,error_message,retry_count,result_data FROM ab_async_task WHERE tenant_id=$1 AND task_code=$2`,
                [beforeAsync[0].tenant_id, submitted.taskCode],
              )
            ).rows;
            expect(delivered).toHaveLength(1);
            expect(delivered[0].error_message).toContain('PERMISSION_DENIED');
            expect(delivered[0].retry_count).toBe(0);
            expect(delivered[0].result_data).toBeNull();
            const queuedAfter = await client.query(
              `SELECT task_code FROM ab_async_task WHERE input_params->>'recordPid'=$1 ORDER BY id`,
              [createdPid],
            );
            const afterAsync = (
              await client.query(`SELECT * FROM mt_${model} WHERE pid=$1`, [createdPid])
            ).rows;
            await info.attach('qo-finite-fields-async-recompute', {
              body: JSON.stringify({
                quotePid: createdPid,
                httpStatus: asyncDenied.status(),
                responseCode: asyncBody.code,
                result: asyncBody.data,
                queuedBefore: queuedBefore.rows,
                queuedAfter: queuedAfter.rows,
                delivery: delivered[0],
                rowUnchangedAfterDelivery:
                  JSON.stringify(beforeAsync) === JSON.stringify(afterAsync),
              }),
              contentType: 'application/json',
            });
            expect(queuedBefore.rows).toHaveLength(0);
            expect(queuedAfter.rows).toEqual([{ task_code: submitted.taskCode }]);
            expect(afterAsync).toEqual(beforeAsync);
          }
          await info.attach(`${domain}-finite-fields-persistence`, {
            body: JSON.stringify({
              grants: finiteSnapshot.grantFields.filter(
                (binding: any) => binding.modelCode === model,
              ),
              defaultScopeType: finiteSnapshot.defaultScopeType,
              scopes: finiteSnapshot.scopes,
              projected,
              deniedWritePreservedRow,
              deniedHttpStatus: denied.status(),
              deniedResponseCode: deniedBody.code,
              publicCommandOutputFieldNames: Object.keys(allowed.data ?? allowed),
              sameSession: true,
            }),
            contentType: 'application/json',
          });
        } finally {
          await client.end();
        }
        await page.reload();
        await page.getByTestId('role-search-input').fill(role.code);
        await page.getByTestId(`role-item-${role.code}`).click();
        await page.getByTestId('permission-right-tab-scope').click();
        await page.getByTestId('role-field-model').selectOption(model);
        for (const level of ['read', 'write']) {
          const grant = page.locator(`[data-testid^="grant-fields-${domain}.cap.${level}-"]`);
          for (const field of readFields)
            await expect(grant.locator(`[data-testid$="-read-${field}"]`)).toBeChecked();
          await expect(grant.locator(`[data-testid$="-write-${excluded}"]`)).not.toBeChecked();
        }
        await page.getByTestId('role-grant-fields').scrollIntoViewIfNeeded();
        await page.screenshot({
          path: info.outputPath(`${domain}-finite-fields-saved.png`),
          fullPage: true,
        });
      } finally {
        await actor.close();
      }
    });
  }
});
