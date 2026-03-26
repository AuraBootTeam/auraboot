import http from 'http';
import fs from 'fs';

const email = process.env.TEST_ADMIN_EMAIL || 'e2e@test.local';
const password = process.env.TEST_ADMIN_PASSWORD || 'E2eTestPass2026!';
const data = `email=${email}&password=${password}&remember=on&redirectTo=/`;
const req = http.request({
  hostname: '127.0.0.1',
  port: 5173,
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
  console.log('Session refreshed OK, cookie length:', value.length);
});
req.write(data);
req.end();
