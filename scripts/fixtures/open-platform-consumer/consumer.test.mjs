// Clean out-of-repo consumer verification for @auraboot/open-platform-sdk.
// Node >= 20 ESM, zero dependencies beyond the installed tarball.
// Runs an in-process fake Open Platform server and asserts the client contract.
import { createServer } from "node:http";
import assert from "node:assert/strict";
import { OpenPlatformClient, OpenPlatformError } from "@auraboot/open-platform-sdk";

const state = {
  tokenRequests: 0,
  commandAttempts: [], // { authorization, idempotencyKey, ifMatch, body }
  listQueries: [],
  dynamicAttempts: [],
  dynamicResults: new Map(),
  record: null,
  version: 0,
};

const acceptedBearers = () => {
  const bearers = [];
  for (let i = 1; i <= state.tokenRequests; i += 1) bearers.push(`Bearer tok_${i}`);
  bearers.push("Bearer static_tok");
  return bearers;
};

const server = createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const body = Buffer.concat(chunks).toString("utf8");
    const auth = req.headers.authorization ?? "";
    const json = (status, payload, headers = {}) => {
      res.writeHead(status, { "Content-Type": "application/json", ...headers });
      res.end(JSON.stringify(payload));
    };

    if (req.method === "POST" && req.url === "/oauth2/token") {
      state.tokenRequests += 1;
      const params = new URLSearchParams(body);
      if (params.get("client_id") === "cid_ok" && params.get("client_secret") === "sec_ok") {
        return json(200, {
          access_token: `tok_${state.tokenRequests}`,
          token_type: "Bearer",
          expires_in: 3600,
          scope: params.get("scope") ?? "",
        });
      }
      return json(401, { code: "invalid_client", message: "bad client credentials" });
    }

    const dynamicPath = "/api/open/v1/resources/fixture.parts";
    if (req.url?.startsWith(dynamicPath)) {
      state.dynamicAttempts.push({ method: req.method, url: req.url, body, headers: req.headers });
      if (!acceptedBearers().includes(auth)) return json(401, { code: "invalid_token" });
      if (req.method === "GET" && req.url.endsWith("/malformed")) {
        res.writeHead(200, { "X-Request-Id": "req_bad_json" }); return res.end("not JSON");
      }
      if (req.method === "GET" && req.url.endsWith("/missing-etag")) return json(200, { pid: "part-1" });
      if (req.method === "GET" && req.url.endsWith("/unavailable")) {
        res.writeHead(503, { "X-Request-Id": "req_unavailable" }); return res.end("unavailable");
      }
      if (req.method === "GET" && req.url.startsWith(dynamicPath + "?")) {
        const query = new URL(req.url, "http://x").searchParams;
        assert.equal(query.get("limit"), "7");
        assert.equal(query.get("sort"), "name"); assert.equal(query.get("direction"), "DESC");
        assert.deepEqual(JSON.parse(query.get("filters")), [{ field: "name", operator: "EQ", value: "A & B / C" }, { field: "code", operator: "IS_NULL" }]);
        assert.equal(query.get("cursor"), "signed+/cursor=");
        return json(200, { items: [{ pid: "part-1", name: "A & B / C" }], hasMore: false, nextCursor: null });
      }
      const key = req.headers["idempotency-key"];
      if (key === "refresh-part") {
        const attempts = state.dynamicAttempts.filter(a => a.headers["idempotency-key"] === key);
        if (attempts.length === 1) return json(401, { code: "invalid_token" });
      }
      if (key === "stale-part") return json(412, { code: "precondition_failed", message: "stale" }, { "X-Request-Id": "req_stale_part" });
      if (key === "busy-part") return json(429, { code: "rate_limit_exceeded" });
      if (state.dynamicResults.has(key)) {
        const saved = state.dynamicResults.get(key);
        if (saved.status === 204) { res.writeHead(204, { "Idempotent-Replay": "true" }); return res.end(); }
        return json(saved.status, saved.body, { ...saved.headers, "Idempotent-Replay": "true" });
      }
      if (req.method === "POST" && req.url === dynamicPath) {
        assert.ok(key); assert.equal(req.headers["if-match"], undefined);
        state.record = { pid: "part-1", ...JSON.parse(body) }; state.version = 1;
        const saved = { status: 201, body: { ...state.record }, headers: { ETag: '"part-v1"', Location: dynamicPath + "/part-1", "X-Request-Id": "req_create" } };
        state.dynamicResults.set(key, saved); return json(saved.status, saved.body, saved.headers);
      }
      if (req.method === "PATCH" && req.url === dynamicPath + "/part-1") {
        assert.ok(key); assert.equal(req.headers["if-match"], '"part-v1"');
        state.record = { ...state.record, ...JSON.parse(body) }; state.version++;
        const saved = { status: 200, body: { ...state.record }, headers: { ETag: '"part-v2"' } };
        state.dynamicResults.set(key, saved); return json(saved.status, saved.body, saved.headers);
      }
      if (req.method === "DELETE" && req.url === dynamicPath + "/part-1") {
        assert.ok(key); assert.equal(req.headers["if-match"], '"part-v2"'); assert.equal(body, "");
        state.record = null; state.dynamicResults.set(key, { status: 204 }); res.writeHead(204); return res.end();
      }
    }
    if (req.method === "POST" && req.url === "/api/open/v1/queries/fixture.summary:execute") {
      assert.ok(acceptedBearers().includes(auth));
      assert.deepEqual(JSON.parse(body), { parameters: { zones: ["A", "B"], active: true, minimum: "1.25" }, limit: 7, cursor: "query+/=" });
      return json(200, { items: [{ total: "3.75", summary: { name: "Parts" } }], total: 1, schemaVersion: 2, hasMore: false, nextCursor: null });
    }

    if (req.method === "GET" && req.url === "/api/open/v1/resources/assets/a_1") {
      if (!acceptedBearers().includes(auth)) {
        return json(401, { code: "invalid_token", message: "expired" }, { "X-Request-Id": "req_unauth" });
      }
      return json(200, { pid: "a_1", assetCode: "AST-1", name: "CNC" }, { ETag: '"v7"', "X-Request-Id": "req_get" });
    }

    if (req.method === "GET" && req.url?.startsWith("/api/open/v1/resources/assets?")) {
      if (!acceptedBearers().includes(auth)) {
        return json(401, { code: "invalid_token", message: "expired" }, { "X-Request-Id": "req_unauth" });
      }
      const query = new URL(req.url, "http://x").searchParams;
      state.listQueries.push(query.get("cursor"));
      if (!query.get("cursor")) {
        return json(200, { items: [{ pid: "a_1" }, { pid: "a_2" }], hasMore: true, nextCursor: "CUR1" });
      }
      return json(200, { items: [{ pid: "a_3" }], hasMore: false, nextCursor: null });
    }

    if (req.method === "POST" && req.url === "/api/open/v1/commands/assets.assign:execute") {
      state.commandAttempts.push({
        authorization: auth,
        idempotencyKey: req.headers["idempotency-key"] ?? null,
        ifMatch: req.headers["if-match"] ?? null,
        body,
      });
      const key = req.headers["idempotency-key"];
      if (key === "idem-412") {
        return json(412, { code: "precondition_failed", message: "stale etag" }, { "X-Request-Id": "req_412" });
      }
      if (key === "idem-refresh") {
        const attemptNo = state.commandAttempts.filter((a) => a.idempotencyKey === "idem-refresh").length;
        if (attemptNo === 1) {
          return json(401, { code: "invalid_token", message: "stale" }, { "X-Request-Id": "req_stale" });
        }
        return json(200, {
          command: "assets.assign",
          idempotentReplay: false,
          resource: { pid: "a_1", assignedTo: "wu" },
          etag: '"v8"',
        }, { "X-Request-Id": "req_cmd" });
      }
      return json(200, { command: "assets.assign", idempotentReplay: true, resource: { pid: "a_1" }, etag: '"v8"' });
    }

    if (req.url === "/api/open/v1/resources/assets/forbidden") {
      if (acceptedBearers().includes(auth)) {
        return json(403, { code: "insufficient_scope", message: "assets.read not granted" }, { "X-Request-Id": "req_403" });
      }
      return json(401, { code: "invalid_token", message: "expired" });
    }

    json(404, { code: "open_api_capability_not_found", message: "no route" });
  });
});

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const baseUrl = `http://127.0.0.1:${server.address().port}`;
const results = [];
const ledger = [];
const check = (name, fn) => results.push({ name, fn });

check("client_credentials mode exchanges a scoped token on first call", async () => {
  const client = new OpenPlatformClient({ baseUrl, clientId: "cid_ok", clientSecret: "sec_ok", scope: "assets.read assets.manage" });
  const page = await client.listResources("assets", 2);
  assert.equal(page.items.length, 2);
  assert.equal(page.hasMore, true);
  assert.equal(state.tokenRequests, 1);
});

check("token is cached across calls; opaque cursor round-trips", async () => {
  const before = state.tokenRequests;
  const client = new OpenPlatformClient({ baseUrl, clientId: "cid_ok", clientSecret: "sec_ok" });
  const page1 = await client.listResources("assets", 2);
  assert.equal(page1.nextCursor, "CUR1");
  const page2 = await client.listResources("assets", 2, page1.nextCursor);
  assert.deepEqual(page2.items.map((i) => i.pid), ["a_3"]);
  assert.equal(state.tokenRequests, before + 1, "exactly one new token exchange for a fresh client");
  assert.deepEqual(state.listQueries.slice(-2), [null, "CUR1"]);
});

check("401 on write refreshes once and preserves Idempotency-Key + If-Match + body", async () => {
  const before = state.tokenRequests;
  const client = new OpenPlatformClient({ baseUrl, clientId: "cid_ok", clientSecret: "sec_ok" });
  const result = await client.assignAsset("a_1", "wu", "idem-refresh", '"v7"');
  assert.equal(result.command, "assets.assign");
  assert.equal(result.idempotentReplay, false);
  assert.equal(result.resource.assignedTo, "wu");
  const attempts = state.commandAttempts.filter((a) => a.idempotencyKey === "idem-refresh");
  assert.equal(attempts.length, 2, "exactly one retry after 401");
  assert.equal(attempts[0].ifMatch, '"v7"');
  assert.equal(attempts[1].ifMatch, '"v7"', "If-Match preserved on retry");
  assert.notEqual(attempts[1].authorization, attempts[0].authorization, "retry carries refreshed token");
  assert.equal(attempts[0].body, attempts[1].body, "identical body on retry");
  assert.equal(state.tokenRequests, before + 2, "initial token + exactly one refresh");
});

check("412 precondition_failed is not retried", async () => {
  const client = new OpenPlatformClient({ baseUrl, clientId: "cid_ok", clientSecret: "sec_ok" });
  await assert.rejects(
    () => client.assignAsset("a_1", "wu", "idem-412", '"stale"'),
    (err) => err instanceof OpenPlatformError && err.status === 412 && err.code === "precondition_failed",
  );
  const attempts = state.commandAttempts.filter((a) => a.idempotencyKey === "idem-412");
  assert.equal(attempts.length, 1, "no retry after 412");
});

check("resource GET returns strong ETag and stable field aliases", async () => {
  const client = new OpenPlatformClient({ baseUrl, clientId: "cid_ok", clientSecret: "sec_ok" });
  const { resource, etag } = await client.getAssetVersioned("a_1");
  assert.equal(resource.pid, "a_1");
  assert.equal(resource.assetCode, "AST-1");
  assert.equal(etag, '"v7"');
});

check("static accessToken mode works and never hits the token endpoint", async () => {
  const before = state.tokenRequests;
  const client = new OpenPlatformClient({ baseUrl, accessToken: "static_tok" });
  const page = await client.listResources("assets", 2);
  assert.equal(page.items.length, 2);
  assert.equal(state.tokenRequests, before, "no token exchange in static mode");
});

check("static mode 401 surfaces immediately without refresh", async () => {
  const client = new OpenPlatformClient({ baseUrl, accessToken: "expired_static" });
  await assert.rejects(
    () => client.listResources("assets", 2),
    (err) => err instanceof OpenPlatformError && err.status === 401 && err.code === "invalid_token",
  );
});

check("errors expose status/code/requestId", async () => {
  const client = new OpenPlatformClient({ baseUrl, accessToken: "static_tok" });
  await assert.rejects(
    () => client.getResource("assets", "forbidden"),
    (err) => {
      assert.ok(err instanceof OpenPlatformError);
      assert.equal(err.status, 403);
      assert.equal(err.code, "insufficient_scope");
      assert.equal(err.requestId, "req_403");
      return true;
    },
  );
});

check("mutually exclusive credential configuration is rejected", async () => {
  assert.throws(() => new OpenPlatformClient({ baseUrl, accessToken: "t", clientId: "c", clientSecret: "s" }));
  assert.throws(() => new OpenPlatformClient({ baseUrl }));
});

check("generic CRUD returns ETag/Location/replay and handles empty 204 deletes", async () => {
  const client = new OpenPlatformClient({ baseUrl, accessToken: "static_tok" });
  const input = { name: "New part", code: "P1" };
  const created = await client.createResource("fixture.parts", input, "create-part");
  assert.equal(created.resource.pid, "part-1"); assert.equal(created.etag, '"part-v1"');
  assert.equal(created.location, "/api/open/v1/resources/fixture.parts/part-1"); assert.equal(created.requestId, "req_create");
  assert.equal(created.idempotentReplay, false);
  const replay = await client.createResource("fixture.parts", input, "create-part");
  assert.equal(replay.idempotentReplay, true); assert.deepEqual(replay.resource, created.resource); assert.equal(replay.etag, created.etag);
  const updated = await client.updateResource("fixture.parts", "part-1", { code: null }, "update-part", created.etag);
  assert.deepEqual(updated.resource, { pid: "part-1", name: "New part", code: null }); assert.equal(updated.etag, '"part-v2"');
  const deleted = await client.deleteResource("fixture.parts", "part-1", "delete-part", updated.etag);
  assert.equal(deleted.idempotentReplay, false); assert.equal(state.record, null);
  const deletedReplay = await client.deleteResource("fixture.parts", "part-1", "delete-part", updated.etag);
  assert.equal(deletedReplay.idempotentReplay, true);
});

check("controlled list preserves public filters, sort and opaque cursor", async () => {
  const client = new OpenPlatformClient({ baseUrl, accessToken: "static_tok" });
  const page = await client.listResources("fixture.parts", { limit: 7, cursor: "signed+/cursor=", sort: "name", direction: "DESC",
    filters: [{ field: "name", operator: "EQ", value: "A & B / C" }, { field: "code", operator: "IS_NULL" }] });
  assert.deepEqual(page.items, [{ pid: "part-1", name: "A & B / C" }]); assert.equal(page.nextCursor, null);
});

check("NamedQuery preserves typed parameters and response schema version", async () => {
  const client = new OpenPlatformClient({ baseUrl, accessToken: "static_tok" });
  const page = await client.executeQuery("fixture.summary", { parameters: { zones: ["A", "B"], active: true, minimum: "1.25" }, limit: 7, cursor: "query+/=" });
  assert.deepEqual(page.items, [{ total: "3.75", summary: { name: "Parts" } }]); assert.equal(page.total, 1); assert.equal(page.schemaVersion, 2);
});

check("generic write refresh preserves intent; 412 and 429 never retry", async () => {
  const client = new OpenPlatformClient({ baseUrl, clientId: "cid_ok", clientSecret: "sec_ok" });
  await client.updateResource("fixture.parts", "part-1", { name: "Refreshed" }, "refresh-part", '"part-v1"');
  const attempts = state.dynamicAttempts.filter(a => a.headers["idempotency-key"] === "refresh-part");
  assert.equal(attempts.length, 2); assert.equal(attempts[0].body, attempts[1].body);
  assert.equal(attempts[1].headers["if-match"], '"part-v1"'); assert.notEqual(attempts[0].headers.authorization, attempts[1].headers.authorization);
  for (const [key, status] of [["stale-part", 412], ["busy-part", 429]]) {
    await assert.rejects(() => client.updateResource("fixture.parts", "part-1", {}, key, '"part-v1"'),
      err => err instanceof OpenPlatformError && err.status === status);
    assert.equal(state.dynamicAttempts.filter(a => a.headers["idempotency-key"] === key).length, 1);
  }
});

check("malformed success and missing ETag fail; non-JSON errors retain status/correlation", async () => {
  const client = new OpenPlatformClient({ baseUrl, accessToken: "static_tok" });
  for (const [pid, status, code] of [["malformed", 200, "invalid_response"], ["missing-etag", 200, "missing_etag"], ["unavailable", 503, "http_error"]]) {
    await assert.rejects(() => client.getResource("fixture.parts", pid), err => err instanceof OpenPlatformError && err.status === status && err.code === code);
  }
});

let failed = 0;
for (const { name, fn } of results) {
  try {
    await fn();
    ledger.push({ name, status: "passed" });
    console.log(`PASS ${name}`);
  } catch (err) {
    failed += 1;
    ledger.push({ name, status: "failed" });
    console.error(`FAIL ${name}`);
    console.error(err);
  }
}
server.close();
console.log("CONSUMER_RESULTS " + JSON.stringify(ledger));
console.log(`SUMMARY pass=${results.length - failed} fail=${failed} tokenRequests=${state.tokenRequests} commandAttempts=${state.commandAttempts.length}`);
if (failed > 0) process.exit(1);
console.log("CONSUMER_RUNTIME_OK");
