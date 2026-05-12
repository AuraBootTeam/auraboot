import http from 'http';
import fs from 'fs';

// Credentials must match tests/helpers/test-accounts.ts (DEFAULT_TEST_ACCOUNT)
const data = JSON.stringify({ email: 'admin@auraboot.com', password: 'Test2026x' });
const options = {
  hostname: 'localhost',
  port: 5173,
  path: '/api/auth/login',
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Content-Length': data.length },
};

const req = http.request(options, (res) => {
  let body = '';
  res.on('data', (d) => (body += d));
  res.on('end', () => {
    const cookies = res.headers['set-cookie'] || [];
    const sessionCookie = cookies.find((c) => c.startsWith('__session='));
    if (!sessionCookie) {
      console.log('No session cookie found, response:', body.slice(0, 200));
      process.exit(1);
    }
    const value = sessionCookie.split('=').slice(1).join('=').split(';')[0];
    const storage = {
      cookies: [
        {
          name: '__session',
          value: value,
          domain: 'localhost',
          path: '/',
          expires: Date.now() / 1000 + 86400,
          httpOnly: true,
          secure: false,
          sameSite: 'Lax',
        },
      ],
      origins: [],
    };
    fs.writeFileSync('tests/storage/admin.json', JSON.stringify(storage, null, 2));
    console.log('Session refreshed successfully');
  });
});
req.write(data);
req.end();
