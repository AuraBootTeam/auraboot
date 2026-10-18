import { expect, type Page } from '@playwright/test';
import { BACKEND_URL, BASE_URL } from './environments';

/** Resource setup only; action/endpoint publication and authority remain UI-driven. */
export async function publishOfficeDeviceResourceFixture(page: Page, resourceCode: string) {
  const field = (source: string, writable = false, required = false) => ({
    source, type: 'string', readable: true, writable, required, nullable: !required,
    operators: [] as string[], sortable: false, referenceResource: null, properties: {}, items: null,
  });
  const definition = {
    code: resourceCode, modelCode: 'tasset_asset', schemaVersion: 1,
    operations: ['list', 'get', 'create', 'update', 'delete'], dataPolicy: 'installation_filter',
    scopes: { list: `${resourceCode}.read`, get: `${resourceCode}.read`,
      create: `${resourceCode}.write`, update: `${resourceCode}.write`, delete: `${resourceCode}.write` },
    fields: {
      pid: { ...field('pid'), type: 'pid', nullable: false },
      assetCode: field('tasset_as_code', true, true), name: field('tasset_as_name', true, true),
      status: field('tasset_as_status'),
      location: { ...field('tasset_as_location', true), operators: ['EQ'] },
      serialNumber: field('tasset_as_serial', true), assignedTo: field('tasset_as_assigned_to', true),
    },
    maxPageSize: 50, maxConditions: 8, timeoutMs: 5000, events: [],
  };
  const root = `${BASE_URL}/api/open-platform/publications/${resourceCode}`;
  const draft = await page.request.put(`${root}/draft`, { data: { expectedRevision: 0, definition } });
  expect(draft.status()).toBe(200);
  const saved = await draft.json();
  expect(saved.code).toBe('0');
  expect(saved.data.draftRevision).toBe(1);
  const published = await page.request.post(`${root}/publish`, { data: { expectedRevision: 1 } });
  expect(published.status()).toBe(200);
  expect((await published.json()).code).toBe('0');
  return { resourceCode, location: `HQ ${resourceCode}` };
}

/** Creates an independently scoped machine caller after its tested contract is published. */
export async function createOfficeDeviceCallerFixture(page: Page, options: {
  code: string; resourceCode: string; location: string; scope: string; deviceName: string;
}) {
  const { code, resourceCode, location, scope, deviceName } = options;
  const name = `Office integration ${code}`;
  const appResponse = await page.request.post(`${BASE_URL}/api/open-platform/applications`, { data: { name } });
  expect(appResponse.status()).toBe(200);
  const app = await appResponse.json();
  expect(app.code).toBe('0');
  expect(app.data.pid).toMatch(/^[0-9A-Z]{26}$/);
  const installResponse = await page.request.post(
    `${BASE_URL}/api/open-platform/applications/${app.data.pid}/installations`, { data: {
      environment: 'development', scopes: [`${resourceCode}.read`, `${resourceCode}.write`, scope], rateLimitPerMinute: 10000,
    } });
  expect(installResponse.status()).toBe(200);
  const installed = await installResponse.json();
  expect(installed.code).toBe('0');
  const installation = installed.data as { pid: string };
  expect(installation.pid).toMatch(/^[0-9A-Z]{26}$/);
  const root = `${BASE_URL}/api/open-platform/installations/${installation.pid}`;
  const grant = await page.request.put(`${root}/resources/${resourceCode}`, { data: {
    operations: ['list', 'get', 'create', 'update', 'delete'],
    readableFields: ['pid', 'assetCode', 'name', 'status', 'location', 'serialNumber', 'assignedTo'],
    writableFields: ['assetCode', 'name', 'location', 'serialNumber', 'assignedTo'],
    rowConstraints: [{ field: 'location', values: [location] }],
  } });
  expect(grant.status()).toBe(200);
  expect((await grant.json()).code).toBe('0');
  const issued = await page.request.post(`${root}/integration-tokens`, {
    headers: { 'Idempotency-Key': `ui-${code}` }, data: { name: 'Owned device caller', expirationMode: 'never' },
  });
  expect(issued.status()).toBe(200);
  const tokenResponse = await issued.json();
  expect(tokenResponse.code).toBe('0');
  const token = tokenResponse.data.token;
  expect(typeof token === 'string' && token.startsWith('ab_at_'), 'Fixture token must be issued').toBe(true);
  const machine = { Authorization: `Bearer ${token}` };
  const createdResponse = await page.request.post(`${BACKEND_URL}/api/open/v1/resources/${resourceCode}`, {
    headers: { ...machine, 'Idempotency-Key': `create-${code}` },
    data: { assetCode: `UI-${code}`, name: deviceName, location },
  });
  expect(createdResponse.status()).toBe(201);
  const device = await createdResponse.json() as { pid: string; name: string; status: string; location: string };
  expect(device).toMatchObject({ name: deviceName, location, status: 'available' });
  expect(device.pid).toMatch(/^[0-9A-Z]{26}$/);
  return { name, installation, root, machine, createdResponse, device };
}
