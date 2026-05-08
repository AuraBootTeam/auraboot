import http from 'http';
import fs from 'fs';

// Port resolution order (highest priority first):
//   1. AURA_FRONTEND_PORT — explicit override (e.g. docker isolated stack)
//   2. PLAYWRIGHT_BASE_URL — parsed from URL like http://localhost:5228
//   3. 5173 — host vite default
function resolvePort() {
  const envPort = process.env.AURA_FRONTEND_PORT;
  if (envPort) return parseInt(envPort, 10);
  const baseUrl = process.env.PLAYWRIGHT_BASE_URL;
  if (baseUrl) {
    const match = baseUrl.match(/:(\d+)(?:\/|$)/);
    if (match) return parseInt(match[1], 10);
  }
  return 5173;
}

const port = resolvePort();
const data = 'email=admin@example.com&password=Test2026x&remember=on&redirectTo=/';
const req = http.request({
  hostname: '127.0.0.1',
  port,
  path: '/login',
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': data.length }
}, (res) => {
  const cookies = res.headers['set-cookie'] || [];
  const sessionCookie = cookies.find(c => c.startsWith('__session='));
  if (!sessionCookie) {
    console.log('No session cookie found');
    process.exit(1);
  }
  const value = sessionCookie.replace('__session=', '').split(';')[0];
  const expires = Math.floor(Date.now() / 1000) + 604800;
  const cookieBase = {
    name: '__session',
    value: value,
    path: '/',
    expires,
    httpOnly: true,
    secure: false,
    sameSite: 'Lax'
  };
  const storage = {
    cookies: [
      { ...cookieBase, domain: '127.0.0.1' },
      { ...cookieBase, domain: 'localhost' },
    ],
    origins: []
  };
  fs.writeFileSync('tests/storage/admin.json', JSON.stringify(storage, null, 2));
  console.log(`Session refreshed OK against :${port}, cookie length: ${value.length}`);
});
req.on('error', (err) => {
  console.error(`Login request failed against :${port} — ${err.message}`);
  process.exit(1);
});
req.write(data);
req.end();
