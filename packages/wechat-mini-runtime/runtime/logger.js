// Log values are summaries by default; secrets never enter either sink.
const PRIVATE =
  /token|authorization|password|secret|cookie|phone|mobile|email|name|address|payload|body|data|context|message|stack|reason/i;
function text(value) {
  return String(value)
    .replace(/Bearer\s+\S+/gi, "Bearer [redacted]")
    .replace(/eyJ[\w-]+\.[\w-]+\.[\w-]+/g, "[redacted]")
    .replace(/https?:\/\/[^\s]+/g, "[url]")
    .replace(/\b1[3-9]\d{9}\b/g, "[phone]")
    .replace(/[\w.+-]+@[\w.-]+\.[a-z]{2,}/gi, "[email]")
    .slice(0, 512);
}
function summarize(value) {
  if (value == null) return value;
  if (Array.isArray(value)) return { type: "array", count: value.length };
  if (typeof value === "object")
    return { type: "object", count: Object.keys(value).length };
  if (typeof value === "string")
    return { type: "string", length: value.length };
  return { type: typeof value };
}
const SAFE = new Set([
  "traceId",
  "serverTraceId",
  "spanId",
  "method",
  "route",
  "durationMs",
  "statusCode",
  "outcome",
  "kind",
  "generation",
  "phase",
  "count",
  "length",
  "type",
  "event",
  "location",
]);
function redact(value, depth = 0) {
  if (depth > 4) return "[truncated]";
  if (Array.isArray(value)) return summarize(value);
  if (!value || typeof value !== "object") return summarize(value);
  const output = {};
  for (const key of Object.keys(value).slice(0, 40)) {
    if (PRIVATE.test(key)) output[key] = "[redacted]";
    else if (SAFE.has(key))
      output[key] =
        typeof value[key] === "string"
          ? text(value[key])
          : typeof value[key] === "number"
            ? value[key]
            : summarize(value[key]);
    else output[key] = summarize(value[key]);
  }
  return output;
}
function createLogger({
  wxApi,
  consoleApi = console,
  namespace = "mini",
  limit = 200,
} = {}) {
  let manager;
  const records = [];
  function emit(level, event, fields = {}) {
    const entry = {
      event: /^[a-z0-9:_.-]{1,64}$/i.test(event) ? event : "log",
      ...redact(fields),
    };
    records.push(entry);
    if (records.length > limit) records.shift();
    try {
      if (consoleApi && consoleApi[level])
        consoleApi[level](`[${namespace}]`, entry);
    } catch (_) {
      /* Optional sink. */
    }
    try {
      if (!manager && wxApi && wxApi.getRealtimeLogManager)
        manager = wxApi.getRealtimeLogManager();
      if (manager && manager[level]) manager[level](entry);
    } catch (_) {
      manager = null; /* Retry acquisition on the next event. */
    }
    return entry;
  }
  return {
    info: (event, fields) => emit("info", event, fields),
    warn: (event, fields) => emit("warn", event, fields),
    error: (event, fields) => emit("error", event, fields),
    snapshot: () => records.slice(),
    capture: (kind, error) => {
      const value = (error && error.stack) || String(error || "");
      const frame = value.match(/(?:at\s+.*?\()?([^\s/()]+\.js:\d+:\d+)\)?/);
      const type =
        (error && error.name) ||
        (/TypeError|ReferenceError|SyntaxError/.exec(value) || [
          typeof error,
        ])[0];
      return emit("error", "global:error", {
        kind,
        type,
        location: frame ? frame[1] : "unavailable",
        error: summarize(error),
      });
    },
  };
}
function globalHandlers(logger) {
  return {
    onError(error) {
      logger.capture("uncaught", error);
    },
    onUnhandledRejection(event) {
      logger.capture("unhandled-rejection", event && event.reason);
    },
  };
}
module.exports = { createLogger, globalHandlers, redact, summarize, text };
