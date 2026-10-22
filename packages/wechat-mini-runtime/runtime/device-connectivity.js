const { target } = require("./environment");
const { trace } = require("./http");
function failure(code) { return Object.assign(new Error(code), { code }); }
// Probe admission is deliberately separate from business-request admission. This
// function never marks a configuration verified and never reads stored identity.
async function probeDeviceConnectivity({ wxApi, config, path = "/health", verify, logger, random }) {
  if (config.target !== "device" || typeof verify !== "function" ||
      typeof config.runtimeName !== "string" || !config.runtimeName ||
      !/^(\/[a-z][a-z0-9-]*)+$/.test(path)) throw failure("CONFIGURATION_REQUIRED");
  // Validate device origin/loopback without claiming a completed health check.
  // The original config remains false and target(config) still blocks business.
  const origin = target({ ...config, deviceHealthVerified: true });
  if (!origin.startsWith("https://")) throw failure("CONFIGURATION_REQUIRED");
  const platform = typeof wxApi.getDeviceInfo === "function" ? wxApi.getDeviceInfo().platform : "";
  if (!["ios", "android", "ohos"].includes(platform)) throw failure("PHYSICAL_DEVICE_REQUIRED");
  const context = trace(random);
  if (logger) logger.info("device:connectivity-start", { traceId: context.traceId, route: path });
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (res) => {
      if (settled) return;
      settled = true;
      let accepted = false;
      try {
        const headers = res && res.header || {};
        const key = Object.keys(headers).find(k => k.toLowerCase() === "x-trace-id");
        accepted = !!res && res.statusCode === 200 && key !== undefined &&
          headers[key] === context.traceId && verify(res.data) === true;
      } catch (_) { accepted = false; }
      if (!accepted) {
        if (logger) logger.warn("device:connectivity-rejected", { traceId: context.traceId, statusCode: res && res.statusCode || 0 });
        reject(failure("DEVICE_CONNECTIVITY_FAILED"));
        return;
      }
      if (logger) logger.info("device:connectivity-verified", { traceId: context.traceId, statusCode: 200 });
      resolve({ origin, path, runtimeName: config.runtimeName, platform, traceId: context.traceId, verifiedAt: new Date().toISOString() });
    };
    try {
      wxApi.request({ url: origin + path, method: "GET", timeout: 12000,
        header: { traceparent: context.traceparent }, success: finish, fail: () => finish(null) });
    } catch (_) { finish(null); }
  });
}
module.exports = { probeDeviceConnectivity };
