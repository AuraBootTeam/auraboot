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

let failed = 0;
for (const { name, fn } of results) {
  try {
    await fn();
    console.log(`PASS ${name}`);
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err);
  }
}
server.close();
console.log(`SUMMARY pass=${results.length - failed} fail=${failed} tokenRequests=${state.tokenRequests} commandAttempts=${state.commandAttempts.length}`);
if (failed > 0) process.exit(1);
console.log("CONSUMER_RUNTIME_OK");
