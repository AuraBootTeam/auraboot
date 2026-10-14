const { summarize } = require("./logger");
// Correlation identifiers only, never credentials or idempotency keys.
function trace(random = Math.random) {
  const hex = (length) =>
    Array.from({ length }, () => Math.floor(random() * 16).toString(16)).join(
      "",
    );
  const traceId = hex(32),
    spanId = hex(16);
  if (/^0+$/.test(traceId) || /^0+$/.test(spanId))
    throw new Error("Invalid trace randomness");
  return { traceId, spanId, traceparent: `00-${traceId}-${spanId}-00` };
}
function route(path) {
  return String(path)
    .split(/[?#]/)[0]
    .split("/")
    .map((part) =>
      /^(?:[0-9]+|[a-f0-9-]{16,})$/i.test(part) || part.includes("%")
        ? ":id"
        : part,
    )
    .join("/");
}
function createHttp({
  wxApi,
  session,
  logger,
  baseUrl,
  decode,
  error,
  random,
  timeout = 15000,
}) {
  return async function request(method, path, data, options = {}) {
    const base = baseUrl();
    const issued = session.snapshot();
    if (options.authRequired && !issued.token) throw error("SESSION_EXPIRED");
    const context = trace(random);
    const started = Date.now();
    const fields = {
      traceId: context.traceId,
      spanId: context.spanId,
      method,
      route: options.route || route(path),
    };
    logger.info("request:start", { ...fields, payload: summarize(data) });
    return new Promise((resolve, reject) => {
      let settled = false;
      function finish(res, failure) {
        if (settled) return;
        settled = true;
        const statusCode = (res && res.statusCode) || 0;
        const headers = (res && res.header) || {};
        const traceHeader = Object.keys(headers).find(
          (key) => key.toLowerCase() === "x-trace-id",
        );
        const traceValue = traceHeader && String(headers[traceHeader]);
        const serverTraceId =
          traceValue &&
          (/^[a-f0-9]{32}$/.test(traceValue) ? traceValue : "invalid-header");
        if (serverTraceId && serverTraceId !== context.traceId)
          logger.warn("trace:mismatch", { ...fields, serverTraceId });
        const stale = !session.current(issued);
        const terminal = {
          ...fields,
          statusCode,
          durationMs: Math.max(0, Date.now() - started),
          ...summarize(res && res.data),
          outcome: stale
            ? "session-changed"
            : failure
              ? "transport-failed"
              : "response",
        };
        logger.info("request:end", terminal);
        let rejected;
        try {
          if (stale) throw error("SESSION_CHANGED");
          if (failure)
            throw error(
              /timeout/i.test(failure.errMsg || "")
                ? "TIMEOUT"
                : "NETWORK_ERROR",
              { method, options },
            );
          if (statusCode === 401) {
            session.clearIfCurrent(issued);
            throw error("SESSION_EXPIRED");
          }
          if (statusCode === 403) throw error("FORBIDDEN");
          const result = decode(res);
          resolve(options.commit ? options.commit(result) : result);
          return;
        } catch (e) {
          rejected = e;
        }
        rejected.traceId = context.traceId;
        rejected.sessionGeneration = session.snapshot().generation;
        logger.warn("request:failed", {
          ...terminal,
          kind: rejected.code || "API_ERROR",
        });
        reject(rejected);
      }
      try {
        wxApi.request({
          url: base + path,
          method,
          data,
          timeout,
          header: {
            "Content-Type": "application/json",
            traceparent: context.traceparent,
            ...(!options.anonymous && issued.token
              ? { Authorization: `Bearer ${issued.token}` }
              : {}),
          },
          success: (res) => finish(res),
          fail: (failure) => finish(null, failure || {}),
        });
      } catch (_) {
        finish(null, { errMsg: "request threw" });
      }
    });
  };
}
module.exports = { createHttp, trace, route };
