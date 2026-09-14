import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:6443").replace(/\/$/, "");
const CLIENT_ID = __ENV.CLIENT_ID || "";
const CLIENT_SECRET = __ENV.CLIENT_SECRET || "";
const PROFILE = __ENV.PROFILE || "smoke";
const apiErrors = new Rate("open_api_errors");
const apiDuration = new Trend("open_api_duration", true);

export const options = {
  ...(PROFILE === "production"
    ? {
        scenarios: {
          open_api: { executor: "constant-vus", vus: 20, duration: "60s" },
        },
        thresholds: {
          open_api_errors: ["rate<0.005"],
          open_api_duration: ["p(95)<250", "p(99)<500"],
          http_reqs: ["rate>20"],
        },
      }
    : {
        scenarios: {
          open_api: { executor: "per-vu-iterations", vus: 1, iterations: 5 },
        },
        thresholds: {
          open_api_errors: ["rate<0.01"],
          open_api_duration: ["p(95)<800"],
        },
      }),
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
};

export function setup() {
  if (!CLIENT_ID || !CLIENT_SECRET)
    throw new Error("CLIENT_ID and CLIENT_SECRET are required");
  const response = http.post(
    `${BASE_URL}/oauth2/token`,
    {
      grant_type: "client_credentials",
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
    },
    { tags: { operation: "token" } },
  );
  const accepted = check(response, {
    "token exchange succeeds": (r) =>
      r.status === 200 && Boolean(r.json("access_token")),
  });
  if (!accepted)
    throw new Error(`Token exchange failed: HTTP ${response.status}`);
  return { token: response.json("access_token") };
}

export default function (data) {
  const response = http.get(`${BASE_URL}/api/open/v1/whoami`, {
    headers: {
      Authorization: `Bearer ${data.token}`,
      "X-Request-Id": `k6-${__VU}-${__ITER}`,
    },
    tags: { operation: "whoami" },
  });
  const accepted = check(response, {
    "whoami succeeds": (r) => r.status === 200,
    "request id is echoed": (r) =>
      r.headers["X-Request-Id"] === `k6-${__VU}-${__ITER}`,
  });
  apiErrors.add(!accepted);
  apiDuration.add(response.timings.duration);
}
