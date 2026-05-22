import { test, expect, type APIRequestContext } from '@playwright/test';
import { existsSync, readFileSync } from 'fs';
import { dirname, resolve } from 'path';
import { fileURLToPath } from 'url';
import { BACKEND_URL } from '../../helpers/environments';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const BACKEND_PLUGIN_ROOT =
  process.env.OSS_PLUGIN_ROOT ??
  process.env.BACKEND_PLUGIN_ROOT ??
  resolve(__dirname, '../../../../plugins');

const REQUIRED_OSS_PLUGINS = [
  {
    name: 'core-announcement',
    pluginId: 'com.auraboot.core-announcement',
    probeModelCode: 'ab_announcement',
    probeCommandCode: 'announcement:create_announcement',
  },
];

async function login(request: APIRequestContext): Promise<string> {
  const loginRes = await request.post(`${BACKEND_URL}/api/auth/login`, {
    data: {
      email: DEFAULT_TEST_ACCOUNT.email,
      password: DEFAULT_TEST_ACCOUNT.password,
    },
  });
  expect(loginRes.ok(), `login failed: ${loginRes.status()}`).toBe(true);
  const loginBody = (await loginRes.json()) as { data?: { jwt?: string } };
  const token = loginBody?.data?.jwt;
  expect(token, 'login response missing jwt').toBeTruthy();
  return token!;
}

test.describe.configure({ mode: 'serial' });

test('import required OSS plugins for OSS E2E profile', async ({ request }) => {
  const token = await login(request);

  for (const plugin of REQUIRED_OSS_PLUGINS) {
    const existingCommandsRes = await request.get(
      `${BACKEND_URL}/api/meta/commands?modelCode=${encodeURIComponent(plugin.probeModelCode)}`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      },
    );
    if (existingCommandsRes.ok()) {
      const existingCommandsBody = (await existingCommandsRes.json()) as {
        data?: Array<{ code?: string }>;
      };
      const commands = Array.isArray(existingCommandsBody?.data) ? existingCommandsBody.data : [];
      if (commands.some((command) => command?.code === plugin.probeCommandCode)) {
        continue;
      }
    }

    const pluginDir = resolve(BACKEND_PLUGIN_ROOT, plugin.name);
    const manifestPath = resolve(pluginDir, 'plugin.json');
    expect(existsSync(manifestPath), `plugin manifest missing: ${manifestPath}`).toBe(true);
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf-8'));
    expect(manifest?.pluginId, `unexpected manifest for ${plugin.name}`).toBe(plugin.pluginId);

    const importRes = await request.post(`${BACKEND_URL}/api/plugins/import/import-directory-sync`, {
      data: {
        path: pluginDir,
        conflictStrategy: 'OVERWRITE',
        validateReferences: true,
        autoDeployProcesses: true,
        autoPublishModels: true,
        autoPublishFields: true,
        autoPublishCommands: true,
        autoPublishPages: true,
        createResourcePermissions: true,
      },
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      timeout: 120_000,
    });

    const rawBody = await importRes.text();
    expect(importRes.ok(), `import ${plugin.name} returned HTTP ${importRes.status()}: ${rawBody}`).toBe(
      true,
    );
    const body = JSON.parse(rawBody) as {
      data?: { success?: boolean; status?: string; errorMessage?: string };
      success?: boolean;
      status?: string;
      errorMessage?: string;
    };
    const result = body?.data && typeof body.data === 'object' ? body.data : body;
    expect(
      result?.success,
      `import ${plugin.name} did not succeed (status=${result?.status ?? '?'}, msg=${
        result?.errorMessage ?? '?'
      })`,
    ).toBe(true);
  }
});
