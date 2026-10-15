import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:6443").replace(/\/$/, "");
const CLIENT_ID = __ENV.CLIENT_ID || "";
const CLIENT_SECRET = __ENV.CLIENT_SECRET || "";
const PROFILE = __ENV.PROFILE || "smoke";
const fixture = __ENV.BUSINESS_FIXTURE_PATH ? JSON.parse(open(__ENV.BUSINESS_FIXTURE_PATH)) : null;
const operations = ["create", "replay", "get", "list", "query", "update", "command", "commandReplay", "handler", "delete"];
const apiErrors = new Rate("open_api_errors");
const apiDuration = new Trend("open_api_duration", true);
const businessErrors = new Rate("business_errors");
const businessDuration = new Trend("business_duration", true);
const businessRequests = new Counter("business_requests");
const thresholds = PROFILE === "production"
  ? { open_api_errors: ["rate<0.005"], open_api_duration: ["p(95)<250", "p(99)<500"], http_reqs: ["rate>20"] }
  : { open_api_errors: ["rate<0.01"], open_api_duration: ["p(95)<800"] };
if (fixture) {
  thresholds.business_errors = PROFILE === "production" ? ["rate<0.005"] : ["rate<0.01"];
  thresholds.business_requests = PROFILE === "production" ? ["rate>20"] : ["count>0"];
  for (const operation of operations) {
    thresholds[`business_requests{operation:${operation}}`] = ["count>0"];
    thresholds[`business_errors{operation:${operation}}`] = PROFILE === "production" ? ["rate<0.005"] : ["rate<0.01"];
    thresholds[`business_duration{operation:${operation}}`] = PROFILE === "production" ? ["p(95)<250", "p(99)<500"] : ["p(95)<800"];
  }
}
export const options = {
  scenarios: { open_api: PROFILE === "production"
    ? { executor: "constant-vus", vus: 20, duration: "60s" }
    : { executor: "per-vu-iterations", vus: 1, iterations: 5 } },
  thresholds,
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
};

function token(clientId, clientSecret) {
  const response = http.post(`${BASE_URL}/oauth2/token`, {
    grant_type: "client_credentials", client_id: clientId, client_secret: clientSecret,
  }, { tags: { operation: "token" } });
  const accepted = check(response, { "token exchange succeeds": r => r.status === 200 && Boolean(r.json("access_token")) });
  if (!accepted) throw new Error(`Token exchange failed: HTTP ${response.status}`);
  return response.json("access_token");
}

export function setup() {
  if (!CLIENT_ID || !CLIENT_SECRET) throw new Error("CLIENT_ID and CLIENT_SECRET are required");
  if (PROFILE === "production" && !fixture) throw new Error("Production SLO requires the dynamic business fixture");
  if (fixture && (fixture.schemaVersion !== 1 || fixture.profile !== "open-platform-dynamic-slo-v1"
      || fixture.resourceCode !== "release.dynamic.parts" || fixture.queryCode !== "release.slo.by-zone"
      || !fixture.integrationToken || fixture.commandCode !== "release.slo.command" || fixture.handlerCode !== "release.slo.handler"
      || !fixture.clientId || !fixture.clientSecret || !fixture.allowedPid || !fixture.hiddenPid
      || fixture.allowedPid === fixture.hiddenPid || !/^slo-[a-f0-9]{20}$/.test(fixture.runId))) {
    throw new Error("Invalid dynamic business fixture");
  }
  return { token: token(CLIENT_ID, CLIENT_SECRET),
    businessToken: fixture ? fixture.integrationToken : null,
    runId: fixture ? `${fixture.runId}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}` : null };
}

function header(response, name) {
  const key = Object.keys(response.headers).find(value => value.toLowerCase() === name.toLowerCase());
  return key ? response.headers[key] : undefined;
}
function body(response) { try { return response.json(); } catch (_) { return null; } }
function strongEtag(value) { return typeof value === "string" && /^"[^"\s]+"$/.test(value); }
function record(value, name, pid) {
  return value && Object.keys(value).sort().join(",") === "name,pid,zone" && value.name === name && value.zone === "A"
    && typeof value.pid === "string" && /^[A-Za-z0-9_-]{1,64}$/.test(value.pid) && !/^\d+$/.test(value.pid)
    && (!pid || value.pid === pid) && value.pid !== fixture.hiddenPid;
}

function businessCall(data, operation, method, path, payload, extraHeaders, expected, assertion) {
  const requestId = `${data.runId}-${__VU}-${__ITER}-${operation}`;
  const response = http.request(method, `${BASE_URL}${path}`, payload === null ? null : JSON.stringify(payload), {
    headers: { Authorization: `Bearer ${data.businessToken}`, "Content-Type": "application/json", "X-Request-Id": requestId, ...extraHeaders },
    tags: { operation },
    responseCallback: http.expectedStatuses(expected),
  });
  const accepted = check(response, {
    [`${operation} business result`]: r => r.status === expected && header(r, "X-Request-Id") === requestId && assertion(r),
  });
  businessRequests.add(1, { operation });
  businessDuration.add(response.timings.duration, { operation });
  businessErrors.add(!accepted, { operation });
  return accepted ? response : null;
}

export function business(data) {
  const path = `/api/open/v1/resources/${fixture.resourceCode}`;
  const key = `${data.runId}-${__VU}-${__ITER}`;
  const name = key;
  const intent = { name, zone: "A" };
  const created = businessCall(data, "create", "POST", path, intent, { "Idempotency-Key": key + "-create" }, 201,
    r => record(body(r), name) && strongEtag(header(r, "ETag")) && [undefined, "false"].includes(header(r, "Idempotent-Replay"))
      && header(r, "Location") === path + "/" + body(r).pid);
  if (!created) return;
  const pid = body(created).pid;
  const etag = header(created, "ETag");
  const replay = businessCall(data, "replay", "POST", path, intent, { "Idempotency-Key": key + "-create" }, 201,
    r => record(body(r), name, pid) && header(r, "ETag") === etag && header(r, "Idempotent-Replay") === "true");
  if (!replay) return;
  const read = businessCall(data, "get", "GET", path + "/" + pid, null, {}, 200,
    r => record(body(r), name, pid) && header(r, "ETag") === etag);
  if (!read) return;
  const listed = businessCall(data, "list", "GET", path + "?limit=10", null, {}, 200, r => {
    const page = body(r);
    return page && Array.isArray(page.items) && page.items.length > 0 && page.items.length <= 10
      && page.items.every(item => record(item, item.name))
      && page.items.some(item => item.pid === fixture.allowedPid);
  });
  if (!listed) return;
  const queried = businessCall(data, "query", "POST", `/api/open/v1/queries/${fixture.queryCode}:execute`,
    { parameters: { zone: "A" }, limit: 10 }, {}, 200, r => {
      const page = body(r);
      return page && Array.isArray(page.items) && page.items.length > 0 && page.items.length <= 10
        && page.items.every(item => Object.keys(item).sort().join(",") === "name,pid" && typeof item.name === "string"
          && typeof item.pid === "string" && item.pid !== fixture.hiddenPid)
        && page.items.some(item => item.pid === fixture.allowedPid);
    });
  if (!queried) return;
  const updated = businessCall(data, "update", "PATCH", path + "/" + pid, { name: name + "-updated" },
    { "Idempotency-Key": key + "-update", "If-Match": etag }, 200,
    r => record(body(r), name + "-updated", pid) && strongEtag(header(r, "ETag")) && header(r, "ETag") !== etag);
  if (!updated) return;
  const commandPath = `/api/open/v1/actions/${fixture.commandCode}:execute`;
  const commandIntent = { targetPid: pid, input: { zone: "A" } };
  const commandHeaders = { "Idempotency-Key": key + "-command", "If-Match": header(updated, "ETag") };
  const command = businessCall(data, "command", "POST", commandPath, commandIntent, commandHeaders, 200,
    r => body(r) && body(r).action === fixture.commandCode && record(body(r).resource, "Business API qualified", pid)
      && body(r).idempotentReplay === false && strongEtag(header(r, "ETag")) && header(r, "ETag") !== header(updated, "ETag"));
  if (!command) return;
  const commandReplay = businessCall(data, "commandReplay", "POST", commandPath, commandIntent, commandHeaders, 200,
    r => body(r) && body(r).action === fixture.commandCode && record(body(r).resource, "Business API qualified", pid)
      && body(r).idempotentReplay === true && header(r, "Idempotent-Replay") === "true" && header(r, "ETag") === header(command, "ETag"));
  if (!commandReplay) return;
  const handler = businessCall(data, "handler", "POST", `/api/open/v1/actions/${fixture.handlerCode}:execute`, { input: { limit: 10 } }, {}, 200,
    r => body(r) && Object.keys(body(r)).sort().join(",") === "countOnPage,hasMore" && Number.isInteger(body(r).countOnPage)
      && body(r).countOnPage > 0 && body(r).countOnPage <= 10 && typeof body(r).hasMore === "boolean");
  if (!handler) return;
  businessCall(data, "delete", "DELETE", path + "/" + pid, null,
    { "Idempotency-Key": key + "-delete", "If-Match": header(command, "ETag") }, 204,
    r => r.body === "" || r.body === null);
}

export default function (data) {
  const requestId = `k6-${__VU}-${__ITER}`;
  const response = http.get(`${BASE_URL}/api/open/v1/whoami`, {
    headers: { Authorization: `Bearer ${data.token}`, "X-Request-Id": requestId }, tags: { operation: "whoami" },
  });
  const accepted = check(response, { "whoami succeeds": r => r.status === 200, "request id is echoed": r => header(r, "X-Request-Id") === requestId });
  apiErrors.add(!accepted);
  apiDuration.add(response.timings.duration);
  if (fixture) business(data);
}

// Export public acceptance metrics; never serialize setup credentials or fixture contents.
export function handleSummary(data) {
  const summary = { metrics: data.metrics, root_group: data.root_group,
    profile: __ENV.PROFILE || "smoke", scope: __ENV.BUSINESS_FIXTURE_PATH ? "dynamic-business" : "baseline-only" };
  const output = { stdout: JSON.stringify({ metrics: data.metrics }, null, 2) + "\n" };
  if (__ENV.SUMMARY_PATH) output[__ENV.SUMMARY_PATH] = JSON.stringify(summary, null, 2);
  return output;
}
