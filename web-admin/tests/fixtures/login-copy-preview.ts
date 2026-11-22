/** Anonymous UI fixture only. It never authenticates or forwards business requests. */
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { COMMUNITY_BRANDING } from '../../app/config/branding';
import { BFF_URL } from '../helpers/environments';
const seed = JSON.parse(readFileSync(new URL('../../../platform/src/main/resources/seed/i18n-base.json', import.meta.url), 'utf8'));
const target = new URL(BFF_URL);
const port = Number(target.port);
if (target.protocol !== 'http:' || !['localhost', '127.0.0.1'].includes(target.hostname) || !port || port === 3500) {
  throw new Error('Select an isolated loopback BFF URL through the central test environment contract; the shared host default is not allowed');
}
const stateFile = process.env.LOGIN_COPY_PREVIEW_STATE;
createServer((req, res) => {
  res.setHeader('Content-Type', 'application/json');
  if (req.method !== 'GET') {
    res.writeHead(503); res.end(JSON.stringify({ code: 'PREVIEW_ONLY', message: 'UI preview only. Authentication is not connected.' })); return;
  }
  const pathname = new URL(req.url!, 'http://127.0.0.1').pathname;
  let data: unknown;
  if (pathname === '/api/runtime/branding') {
    const state = stateFile ? JSON.parse(readFileSync(stateFile, 'utf8')) : {};
    res.end(JSON.stringify({ branding: { ...COMMUNITY_BRANDING, ...(state.template ? { authAppearance: { version: 1, template: state.template, defaultLocale: 'zh-CN', theme: { mode: state.dark ? 'dark' : 'light' }, ...(state.content ? { content: state.content } : {}) } } : {}) } })); return;
  }
  if (pathname.startsWith('/api/i18n/')) {
    const locale = pathname.split('/').at(-1)!;
    data = Object.fromEntries(seed.filter((row: Record<string, string>) => row[locale] !== undefined).map((row: Record<string, string>) => [row.key, row[locale]]));
  } else if (pathname === '/api/bootstrap/status') data = { initialized: true, inProgress: false, missingParts: [] };
  else if (pathname === '/api/auth/access-policy') data = { deploymentMode: 'single', userRegistrationPolicy: 'closed', tenantProvisioningPolicy: 'disabled', partyCreationPolicy: 'approval_required', partyInvitationEnabled: false, actorSwitchEnabled: false };
  else if (pathname === '/api/auth/login/channel-options') data = [{ code: 'EMAIL_PASSWORD', kind: 'password', displayName: 'Email and password', providerType: null }];
  else { res.writeHead(404); res.end(JSON.stringify({ code: 'NOT_FOUND', message: 'No fixture for this request' })); return; }
  res.end(JSON.stringify({ code: '0', data }));
}).listen(port, '127.0.0.1', () => console.log(`Anonymous login UI fixture: ${port}. No authentication or business writes.`));
