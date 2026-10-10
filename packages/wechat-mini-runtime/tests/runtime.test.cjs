const test = require("node:test");
const assert = require("node:assert/strict");
const runtime = require("../runtime");
function setup() {
  const storage = new Map([["token", "old-token"]]);
  const calls = [],
    printed = [];
  const wxApi = {
    getStorageSync: (k) => storage.get(k),
    setStorageSync: (k, v) => storage.set(k, v),
    removeStorageSync: (k) => storage.delete(k),
    request: (o) => calls.push(o),
  };
  let invalidated = 0;
  const session = runtime.createSession({
    wxApi,
    tokenKey: "token",
    invalidate: () => invalidated++,
  });
  const logger = runtime.createLogger({
    wxApi,
    consoleApi: {
      info: (...args) => printed.push(args),
      warn: (...args) => printed.push(args),
    },
  });
  const request = runtime.createHttp({
    wxApi,
    session,
    logger,
    baseUrl: () => "https://fixture.invalid",
    random: () => 0.5,
    error: (code) => Object.assign(new Error(code), { code }),
    decode(res) {
      if (res.data.code !== 0)
        throw Object.assign(new Error("business error"), {
          code: "BUSINESS_ERROR",
        });
      return res.data.data;
    },
  });
  return {
    calls,
    storage,
    session,
    logger,
    request,
    printed,
    invalidated: () => invalidated,
  };
}
test("one W3C traceId correlates start, end and response without requestId", async () => {
  const f = setup();
  const pending = f.request(
    "GET",
    "/orders/9007199254740993123?phone=13812345678",
  );
  const sent = f.calls[0];
  assert.match(sent.header.traceparent, /^00-[a-f0-9]{32}-[a-f0-9]{16}-00$/);
  assert.equal(sent.header["X-Request-Id"], undefined);
  assert.equal(sent.header.Authorization, "Bearer old-token");
  sent.success({
    statusCode: 200,
    header: { "X-Trace-Id": sent.header.traceparent.split("-")[1] },
    data: { code: 0, data: { token: "secret" } },
  });
  assert.deepEqual(await pending, { token: "secret" });
  const records = f.logger.snapshot();
  assert.equal(records.length, 2);
  assert.equal(records[0].traceId, records[1].traceId);
  assert.equal(records[1].statusCode, 200);
  assert.ok(records[1].durationMs >= 0);
  assert.equal(records[0].route, "/orders/:id");
  assert.doesNotMatch(
    JSON.stringify(f.printed),
    /old-token|13812345678|secret|requestId/,
  );
});
for (const statusCode of [200, 401])
  test(`stale ${statusCode} cannot return data, erase new token or retry POST`, async () => {
    const f = setup();
    const pending = f.request("POST", "/orders/1/shipments", { quantity: 1 });
    f.session.replace("new-token");
    f.calls[0].success({
      statusCode,
      data: { code: 0, data: { private: "old-user" } },
    });
    await assert.rejects(pending, { code: "SESSION_CHANGED" });
    assert.equal(f.storage.get("token"), "new-token");
    assert.equal(f.calls.length, 1);
    assert.equal(f.invalidated(), 1);
  });
test("current 401 expires session and invalidates caches exactly once", async () => {
  const f = setup();
  const pending = f.request("GET", "/profile");
  f.calls[0].success({ statusCode: 401 });
  await assert.rejects(pending, { code: "SESSION_EXPIRED" });
  assert.equal(f.storage.has("token"), false);
  assert.equal(f.invalidated(), 1);
});
test("ABA token rotation still invalidates the old request", async () => {
  const f = setup();
  const pending = f.request("GET", "/profile");
  f.session.replace("other");
  f.session.replace("old-token");
  f.calls[0].success({ statusCode: 200, data: { code: 0, data: {} } });
  await assert.rejects(pending, { code: "SESSION_CHANGED" });
});
test("anonymous login commits token atomically and concurrent old login cannot replace it", async () => {
  const f = setup();
  const commit = (result) => {
    f.session.replace(result.token);
    return result;
  };
  const first = f.request("POST", "/login", {}, { anonymous: true, commit });
  const second = f.request("POST", "/login", {}, { anonymous: true, commit });
  assert.equal(f.calls[0].header.Authorization, undefined);
  f.calls[0].success({
    statusCode: 200,
    data: { code: 0, data: { token: "winner" } },
  });
  f.calls[1].success({
    statusCode: 200,
    data: { code: 0, data: { token: "loser" } },
  });
  await first;
  await assert.rejects(second, { code: "SESSION_CHANGED" });
  assert.equal(f.storage.get("token"), "winner");
});
test("transport failure emits one terminal event despite duplicate native callbacks", async () => {
  const f = setup();
  const pending = f.request("POST", "/orders/1", { secret: "raw" });
  f.calls[0].fail({ errMsg: "timeout Bearer unsafe" });
  f.calls[0].success({ statusCode: 200 });
  await assert.rejects(pending, { code: "TIMEOUT" });
  assert.equal(
    f.logger.snapshot().filter((e) => e.event === "request:end").length,
    1,
  );
  assert.equal(f.calls.length, 1);
  assert.doesNotMatch(JSON.stringify(f.printed), /unsafe|raw/);
});
test("trace mismatch is visible and never silently rewritten to the server trace", async () => {
  const f = setup();
  const pending = f.request("GET", "/profile");
  f.calls[0].success({
    statusCode: 200,
    header: { "x-trace-id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" },
    data: { code: 0, data: {} },
  });
  await pending;
  const mismatch = f.logger
    .snapshot()
    .find((e) => e.event === "trace:mismatch");
  assert.equal(mismatch.serverTraceId, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  assert.notEqual(mismatch.traceId, mismatch.serverTraceId);
});
test("realtime acquisition can recover after privacy refusal; both sinks receive redacted records", () => {
  let attempts = 0;
  const remote = [],
    local = [];
  const logger = runtime.createLogger({
    wxApi: {
      getRealtimeLogManager() {
        if (++attempts === 1) throw new Error("privacy");
        return { info: (entry) => remote.push(entry) };
      },
    },
    consoleApi: { info: (...args) => local.push(args) },
  });
  const fields = {
    token: "opaque-secret",
    profile: { name: "Alice", phone: "13812345678" },
    data: { email: "a@b.com" },
    method: "POST",
  };
  logger.info("login", fields);
  logger.info("login", fields);
  assert.equal(attempts, 2);
  assert.equal(remote.length, 1);
  assert.equal(local.length, 2);
  assert.doesNotMatch(
    JSON.stringify({ remote, local }),
    /opaque-secret|Alice|13812345678|a@b.com/,
  );
});
test("global handlers capture error category and source location without raw error text", () => {
  const logger = runtime.createLogger({ consoleApi: {} });
  const handlers = runtime.globalHandlers(logger);
  const error = new TypeError("name=Alice phone=13812345678");
  error.stack = "TypeError: name=Alice\n at page (/private/user/app.js:12:3)";
  handlers.onError(error);
  handlers.onUnhandledRejection({ reason: "Bearer secret" });
  const records = logger.snapshot();
  assert.equal(records[0].type, "TypeError");
  assert.equal(records[0].location, "app.js:12:3");
  assert.equal(records[1].kind, "unhandled-rejection");
  assert.doesNotMatch(
    JSON.stringify(records),
    /Alice|13812345678|secret|private/,
  );
});
test("custom exception names never enter console, realtime, or retained logs", () => {
  const local = [],
    remote = [];
  const logger = runtime.createLogger({
    consoleApi: { error: (_, entry) => local.push(entry) },
    wxApi: {
      getRealtimeLogManager: () => ({ error: (entry) => remote.push(entry) }),
    },
  });
  const error = new Error("private-payload");
  error.name = "Customer Alice: account opaque-secret";
  error.stack = "Customer Alice: private-payload";
  logger.capture("uncaught", error);
  logger.capture(
    "unhandled-rejection",
    "Customer Alice TypeError private-payload",
  );
  logger.capture("uncaught", "RangeError: private-payload");
  assert.deepEqual(
    logger.snapshot().map((entry) => entry.type),
    ["Error", "Error", "RangeError"],
  );
  assert.equal(local.length, 3);
  assert.equal(remote.length, 3);
  assert.doesNotMatch(
    JSON.stringify({ local, remote, retained: logger.snapshot() }),
    /Alice|opaque-secret|private-payload/,
  );
});
test("device configuration rejects loopback and unverified connectivity", () => {
  for (const host of ["localhost", "127.0.0.1", "127.1", "0.0.0.0", "[::1]"])
    assert.throws(
      () =>
        runtime.target({
          apiBaseUrl: `http://${host}:5000`,
          target: "device",
          deviceHealthVerified: true,
        }),
      { code: "DEVICE_LOOPBACK_FORBIDDEN" },
    );
  assert.throws(
    () =>
      runtime.target({
        apiBaseUrl: "http://192.168.1.10:5000",
        target: "device",
      }),
    { code: "CONFIGURATION_REQUIRED" },
  );
  assert.equal(
    runtime.target({
      apiBaseUrl: "http://192.168.1.10:5000",
      target: "device",
      deviceHealthVerified: true,
    }),
    "http://192.168.1.10:5000",
  );
});
test("trial version is packaged build; only release uses official runtime version", () => {
  assert.equal(
    runtime.badge(
      { version: "1.0", appEnv: "local", target: "simulator" },
      { envVersion: "trial", version: "9.9" },
    ).number,
    "v1.0",
  );
  assert.equal(
    runtime.badge({ version: "1.0" }, { envVersion: "release", version: "9.9" })
      .number,
    "v9.9",
  );
});

test("an invalid server trace header cannot leak business text through the log allowlist", async () => {
  const f = setup();
  const pending = f.request("GET", "/profile");
  f.calls[0].success({
    statusCode: 200,
    header: { "x-trace-id": "Sensitive Customer" },
    data: { code: 0, data: {} },
  });
  await pending;
  assert.equal(
    f.logger.snapshot().find((e) => e.event === "trace:mismatch").serverTraceId,
    "invalid-header",
  );
  assert.doesNotMatch(JSON.stringify(f.printed), /Sensitive Customer/);
});
