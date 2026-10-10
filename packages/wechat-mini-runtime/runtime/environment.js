function target(config) {
  const base = String(config.apiBaseUrl || "").replace(/\/$/, "");
  const parsed =
    /^(https?):\/\/(\[[a-f0-9:.]+\]|[a-z0-9][a-z0-9.-]*)(?::(\d{1,5}))?$/i.exec(
      base,
    );
  if (
    !parsed ||
    (parsed[3] && (+parsed[3] < 1 || +parsed[3] > 65535)) ||
    !["simulator", "device"].includes(config.target)
  ) {
    throw Object.assign(new Error("Explicit API origin and target required"), {
      code: "CONFIGURATION_REQUIRED",
    });
  }
  const loopback =
    /^https?:\/\/(localhost\.?|127(?:\.\d+){0,3}|0\.0\.0\.0|\[(?:::1|::|::ffff:127\.0\.0\.1)\])(?::|$)/i.test(
      base,
    );
  if (config.target === "device" && loopback)
    throw Object.assign(new Error("Device cannot use loopback"), {
      code: "DEVICE_LOOPBACK_FORBIDDEN",
    });
  if (config.target === "device" && config.deviceHealthVerified !== true)
    throw Object.assign(
      new Error("Device connectivity verification required"),
      { code: "CONFIGURATION_REQUIRED" },
    );
  return base;
}
function badge(config, mini = {}, labels = {}) {
  const official =
    mini.envVersion === "release" &&
    typeof mini.version === "string" &&
    mini.version.trim();
  const version = official || config.version || "";
  return {
    number: `v${String(version).replace(/^v/, "")}`,
    edition: labels[mini.envVersion] || mini.envVersion || "build",
    environment: config.appEnv || config.runtimeName || "",
    target: config.target || "",
    label: official
      ? labels.releaseLabel || "Release version"
      : labels.buildLabel || "Build version",
    source: official
      ? labels.releaseSource || "WeChat runtime"
      : labels.buildSource || "Packaged build",
    text: `v${String(version).replace(/^v/, "")} · ${config.appEnv || config.runtimeName || ""} · ${config.target || ""}`,
  };
}
module.exports = { target, badge };
