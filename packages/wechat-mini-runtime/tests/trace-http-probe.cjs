// Real HTTP probe driven by the shared client; the transport adapter is Node fetch,
// so this evidence does not claim WeChat device transport or business acceptance.
const assert = require("node:assert/strict");
const runtime = require("../runtime/index.js");
const base = process.argv[2];
assert.match(base || "", /^http:\/\/127\.0\.0\.1:\d+$/);
const storage = new Map();
const wxApi = {
  getStorageSync: (key) => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: (key) => storage.delete(key),
  request(options) {
    fetch(options.url, {
      method: options.method,
      headers: options.header,
      signal: AbortSignal.timeout(options.timeout),
    })
      .then(async (response) =>
        options.success({
          statusCode: response.status,
          header: Object.fromEntries(response.headers),
          data: await response.json(),
        }),
      )
      .catch(() => options.fail({ errMsg: "network failure" }));
  },
};
const logger = runtime.createLogger({ consoleApi: {} });
const session = runtime.createSession({ wxApi, tokenKey: "probe-token" });
const request = runtime.createHttp({
  wxApi,
  logger,
  session,
  baseUrl: () => base,
  decode: (response) => {
    assert.equal(response.statusCode, 200);
    assert.match(response.header["x-trace-id"] || "", /^[a-f0-9]{32}$/);
    return { ...response.data, wireTraceId: response.header["x-trace-id"] };
  },
  error: (code) => Object.assign(new Error(code), { code }),
});
(async () => {
  const result = await request("GET", "/mini-trace-probe");
  const records = logger.snapshot();
  assert.deepEqual(
    records.map((entry) => entry.event),
    ["request:start", "request:end"],
  );
  const traceId = records[0].traceId;
  assert.equal(records[1].traceId, traceId);
  assert.equal(
    result.traceId,
    traceId,
    "actual server span must continue the client trace",
  );
  assert.equal(
    result.responseTraceId,
    traceId,
    "production response filter must expose the same trace",
  );
  assert.equal(
    result.wireTraceId,
    traceId,
    "actual response header must survive every HTTP hop",
  );
  assert.equal(result.parentSpanId, records[0].spanId);
  assert.match(result.serverSpanId, /^[a-f0-9]{16}$/);
  assert.notEqual(result.serverSpanId, records[0].spanId);
  assert.equal(records[1].statusCode, 200);
  assert.ok(records[1].durationMs >= 0);
  process.stdout.write(
    JSON.stringify({
      surface: "api",
      dependencies: "real-stack",
      driver: "http",
      traceId,
      records,
    }) + "\n",
  );
})().catch((error) => {
  process.stderr.write(error.stack + "\n");
  process.exitCode = 1;
});
