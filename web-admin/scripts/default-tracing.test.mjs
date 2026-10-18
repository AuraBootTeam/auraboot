import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import YAML from 'yaml';
import { createServer } from 'node:http';
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { once } from 'node:events';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
// Unit imports do not register a global SDK; launcher behavior is exercised in fresh child processes.
process.env.OTEL_SDK_DISABLED = 'true';
const { LocalSpanExporter } = await import('../instrumentation.server.mjs');
const span = (id, extra = {}) => ({
  name: '/api/example?token=private',
  spanContext: () => ({ traceId: id, spanId: '0123456789abcdef' }),
  kind: 1,
  startTime: [1, 0],
  duration: [0, 10],
  status: { code: 2 },
  events: [],
  attributes: {
    'request.id': 'request-1',
    'url.path': '/api/example?token=private',
    authorization: 'private',
    'db.statement': 'private',
    ...extra,
  },
});
const send = (exporter, spans) => new Promise((resolve) => exporter.export(spans, resolve));

test('local trace index survives missing collector configuration and excludes sensitive attributes', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'aura-span-'));
  try {
    const exporter = new LocalSpanExporter(dir, 'test');
    assert.equal((await send(exporter, [span('0123456789abcdef0123456789abcdef')])).code, 0);
    await exporter.shutdown();
    const text = await readFile(join(dir, 'test.jsonl'), 'utf8');
    const row = JSON.parse(text);
    assert.equal(row.traceId, '0123456789abcdef0123456789abcdef');
    assert.equal(row.attributes['request.id'], 'request-1');
    assert.equal(row.status, 2);
    assert.ok(!text.includes('private'));
    assert.equal((await stat(join(dir, 'test.jsonl'))).mode & 0o777, 0o600);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('rotation bounds the local index while retaining recent spans and reports I/O failure', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'aura-span-'));
  try {
    const exporter = new LocalSpanExporter(dir, 'test', 512);
    for (let i = 0; i < 20; i++) assert.equal((await send(exporter, [span(String(i))])).code, 0);
    assert.equal((await readdir(dir)).length, 5);
    assert.equal(
      JSON.parse((await readFile(join(dir, 'test.jsonl'), 'utf8')).trim().split('\n').at(-1))
        .traceId,
      '19',
    );
    const blocked = join(dir, 'blocked');
    await writeFile(blocked, 'file');
    const unavailable = new LocalSpanExporter(blocked, 'test');
    assert.equal((await send(unavailable, [span('failure')])).code, 1);
    await unavailable.shutdown();
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

for (const remote of [false, true])
  test(`ordinary Node launch retains and propagates traces with remote export ${remote ? 'configured' : 'unset'}`, async () => {
    const received = [];
    const collector = createServer(async (req, res) => {
      const chunks = [];
      for await (const bytes of req) chunks.push(bytes);
      received.push(JSON.parse(Buffer.concat(chunks).toString()));
      res.setHeader('Content-Type', 'application/json');
      res.end('{}');
    });
    if (remote) {
      collector.listen(0, '127.0.0.1');
      await once(collector, 'listening');
    }
    const dir = await mkdtemp(join(tmpdir(), 'aura-trace-launch-'));
    const fixture = join(dir, 'server.mjs');
    await writeFile(
      fixture,
      `import http from 'node:http';
const server = http.createServer(async (req, res) => {
  if (req.url === '/inner') { res.end(req.headers.traceparent || 'missing'); return; }
  const result = await fetch('http://127.0.0.1:' + server.address().port + '/inner');
  res.end(await result.text());
});
server.listen(0, '127.0.0.1', () => console.log(server.address().port));`,
    );
    const env = Object.fromEntries(
      Object.entries(process.env).filter(([key]) => !key.startsWith('OTEL_')),
    );
    const child = spawn(
      process.execPath,
      ['--import', resolve(root, 'instrumentation.server.mjs'), fixture],
      {
        cwd: root,
        env: {
          ...env,
          AURA_TRACE_LOG_DIR: dir,
          ...(remote
            ? {
                OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: `http://127.0.0.1:${collector.address().port}/v1/traces`,
              }
            : {}),
        },
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    );
    let errors = '';
    child.stderr.on('data', (bytes) => {
      errors += bytes;
    });
    try {
      const [bytes] = await once(child.stdout, 'data');
      const port = Number(bytes.toString().trim());
      assert.ok(port > 0);
      const parent = '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01';
      const response = await fetch('http://127.0.0.1:' + port + '/outer', {
        headers: { traceparent: parent },
      });
      assert.match(
        await response.text(),
        /^00-0123456789abcdef0123456789abcdef-[a-f0-9]{16}-01$/,
        errors,
      );
      child.kill('SIGTERM');
      await once(child, 'exit');
      const rows = (await readFile(join(dir, 'auraboot-ssr.jsonl'), 'utf8'))
        .trim()
        .split('\n')
        .map(JSON.parse);
      assert.ok(rows.length >= 3, errors);
      assert.ok(
        rows.every((row) => row.traceId === '0123456789abcdef0123456789abcdef'),
        errors,
      );
      assert.ok(!errors.includes('ECONNREFUSED'), errors);
      if (remote) {
        const spans = received.flatMap((batch) =>
          batch.resourceSpans.flatMap((resource) =>
            resource.scopeSpans.flatMap((scope) => scope.spans),
          ),
        );
        assert.ok(spans.length >= 3);
        assert.ok(spans.every((span) => span.traceId === '0123456789abcdef0123456789abcdef'));
      }
    } finally {
      if (child.exitCode === null) child.kill('SIGKILL');
      if (remote) {
        collector.closeAllConnections();
        collector.close();
      }
      await rm(dir, { recursive: true, force: true });
    }
  });

test('release Compose defaults enable tracing and persist frontend and backend indexes', async () => {
  for (const name of [
    'docker-compose.yml',
    'docker-compose.pull.yml',
    'scripts/deploy/oss-remote/docker-compose.remote.yml',
  ]) {
    const file = resolve(root, '..', name);
    const compose = YAML.parse(await readFile(file, 'utf8'));
    assert.equal(
      compose.services.backend.environment.MANAGEMENT_TRACING_ENABLED,
      '${MANAGEMENT_TRACING_ENABLED:-true}',
    );
    assert.equal(compose.services.backend.environment.AURA_TRACE_LOG_DIR, '/app/data/traces');
    assert.ok(compose.services.backend.volumes.some((value) => value.endsWith(':/app/data')));
    assert.equal(compose.services.frontend.environment.AURA_TRACE_LOG_DIR, '/app/logs/traces');
    const mount = compose.services.frontend.volumes.find((value) =>
      value.endsWith(':/app/logs/traces'),
    );
    assert.ok(mount);
    assert.ok(Object.hasOwn(compose.volumes, mount.split(':')[0]));
  }
  const dockerfile = await readFile(resolve(root, 'Dockerfile'), 'utf8');
  assert.ok(dockerfile.includes('"--import", "./instrumentation.server.mjs"'));
});

test('only pre-sanitized loader exceptions retain their original diagnostic stack', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'aura-span-errors-'));
  try {
    const exporter = new LocalSpanExporter(dir, 'test');
    const loader = {
      ...span('loader'),
      instrumentationScope: { name: 'auraboot-ssr' },
      events: [
        {
          name: 'exception',
          attributes: {
            'exception.type': 'TimeoutError',
            'exception.message': 'operation timed out',
            'exception.stacktrace': 'TimeoutError: operation timed out\n    at loader',
          },
        },
      ],
    };
    const automatic = {
      ...span('auto'),
      instrumentationScope: { name: 'http-client' },
      events: [{ name: 'exception', attributes: { 'exception.message': 'private' } }],
    };
    assert.equal((await send(exporter, [loader, automatic])).code, 0);
    const text = await readFile(join(dir, 'test.jsonl'), 'utf8');
    const rows = text.trim().split('\n').map(JSON.parse);
    assert.ok(rows[0].events[0].stack.includes('at loader'));
    assert.equal(rows[0].events[0].exceptionType, 'TimeoutError');
    assert.deepEqual(rows[1].events, []);
    assert.ok(!text.includes('private'));
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
