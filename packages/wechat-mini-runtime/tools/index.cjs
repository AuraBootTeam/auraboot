const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");
const { target } = require("../runtime/environment");
const { redact } = require("../runtime/logger");
const MARKER = ".aura-mini-project.json";
function contained(parent, child) {
  return child === parent || child.startsWith(parent + path.sep);
}
function prepareProject({
  source,
  out,
  config,
  mode = "real-stack",
  identity,
  purpose = "test",
}) {
  source = fs.realpathSync(source);
  out = path.resolve(out);
  let ancestor = out;
  const suffix = [];
  while (!fs.existsSync(ancestor)) {
    suffix.unshift(path.basename(ancestor));
    ancestor = path.dirname(ancestor);
  }
  out = path.join(fs.realpathSync(ancestor), ...suffix);
  if (contained(source, out) || contained(out, source))
    throw new Error("Project output must be separate from source");
  if (!identity || !["fixture", "real-stack"].includes(mode))
    throw new Error("Explicit source identity and data mode required");
  target(config);
  if (!config.version || !config.runtimeName)
    throw new Error("Version and runtime identity required");
  if (
    /^(online|production)$/.test(config.appEnv) &&
    purpose !== "release-build"
  )
    throw new Error("Test project cannot target production");
  if (fs.existsSync(out)) {
    const markerPath = path.join(out, MARKER);
    if (!fs.existsSync(markerPath))
      throw new Error("Refuse to overwrite unowned project output");
    const old = JSON.parse(fs.readFileSync(markerPath));
    if (old.source !== source || old.kind !== "aura-mini-test")
      throw new Error("Output belongs to another source");
    fs.rmSync(out, { recursive: true });
  }
  fs.mkdirSync(out, { recursive: true });
  const excluded = new Set([
    "node_modules",
    "tests",
    "golden",
    ".git",
    ".DS_Store",
  ]);
  fs.cpSync(source, out, {
    recursive: true,
    filter: (entry) =>
      !path
        .relative(source, entry)
        .split(path.sep)
        .some((part) => excluded.has(part)),
  });
  const sourceHash = crypto.createHash("sha256");
  function fingerprint(dir) {
    for (const name of fs.readdirSync(dir).sort()) {
      if (excluded.has(name)) continue;
      const file = path.join(dir, name);
      if (fs.lstatSync(file).isSymbolicLink())
        throw new Error("Test sources cannot contain symlinks");
      if (fs.statSync(file).isDirectory()) fingerprint(file);
      else {
        sourceHash.update(path.relative(source, file));
        sourceHash.update(fs.readFileSync(file));
      }
    }
  }
  fingerprint(source);
  const sourceIdentity = { ...identity, sha256: sourceHash.digest("hex") };
  const generated = { ...config, dataMode: mode, sourceIdentity };
  fs.writeFileSync(
    path.join(out, "config.js"),
    "// Generated test project. Do not edit.\nmodule.exports = " +
      JSON.stringify(generated, null, 2) +
      ";\n",
  );
  const manifest = {
    kind: "aura-mini-test",
    source,
    out,
    identity: sourceIdentity,
    mode,
    purpose,
    config: generated,
  };
  fs.writeFileSync(
    path.join(out, MARKER),
    JSON.stringify(manifest, null, 2) + "\n",
  );
  return manifest;
}
function preflight({ cli, project, port, execute = execFileSync }) {
  if (
    !fs.existsSync(path.join(project, "project.config.json")) ||
    !Number.isInteger(port) ||
    port < 1024 ||
    port > 65535
  )
    throw new Error("Invalid DevTools project or bridge port");
  const output = String(
    execute(cli, ["islogin"], { encoding: "utf8", timeout: 15000 }),
  );
  if (!/"(?:login|islogin)"\s*:\s*true/i.test(output))
    throw new Error("DevTools login not confirmed");
  return { project: fs.realpathSync(project), port, loggedIn: true };
}
function monitorConsole(mp, { allow = () => false, limit = 500 } = {}) {
  const records = [],
    errors = [];
  let overflow = false;
  const handler = (msg) => {
    const raw = (msg.args || [])
      .map((arg) => (typeof arg === "string" ? arg : JSON.stringify(arg)))
      .join(" ");
    const failure =
      msg.type === "error" ||
      /TypeError|ReferenceError|SyntaxError|not a function|Cannot read/i.test(
        raw,
      );
    const entry = {
      level: msg.type,
      failure,
      allowed: failure && allow(msg),
      fields: (msg.args || []).map((value) => redact(value)),
    };
    if (records.length < limit) records.push(entry);
    else overflow = true;
    if (failure && !entry.allowed) errors.push(entry);
  };
  mp.on("console", handler);
  return {
    records,
    errors,
    assertClean() {
      if (overflow || errors.length)
        throw new Error(
          `Mini console rejected: ${errors.length} errors; overflow=${overflow}`,
        );
    },
    detach() {
      if (mp.off) mp.off("console", handler);
    },
  };
}
async function captureEvidence(
  mp,
  { directory, scenario, identity, mode, monitor },
) {
  if (
    !/^[a-z0-9_-]+$/i.test(scenario) ||
    !identity ||
    !["fixture", "real-stack"].includes(mode)
  )
    throw new Error("Evidence identity required");
  fs.mkdirSync(directory, { recursive: true });
  const filename = path.join(directory, scenario + ".png");
  await mp.screenshot({ path: filename });
  const manifest = {
    scenario,
    identity,
    mode,
    screenshot: filename,
    sha256: crypto
      .createHash("sha256")
      .update(fs.readFileSync(filename))
      .digest("hex"),
    console: monitor ? monitor.records : [],
    consoleErrors: monitor ? monitor.errors.length : null,
    visualReview: "pending",
    verdict: "captured",
  };
  fs.writeFileSync(
    path.join(directory, scenario + ".json"),
    JSON.stringify(manifest, null, 2) + "\n",
  );
  if (monitor) monitor.assertClean();
  return manifest;
}
async function verifyBridge(mp, expected) {
  const actual = await mp.evaluate(() => {
    const app = getApp();
    return app && app.miniRuntimeIdentity;
  });
  for (const key of [
    "apiBaseUrl",
    "runtimeName",
    "target",
    "version",
    "dataMode",
  ]) {
    if (!expected[key] || (actual && actual[key] !== expected[key]))
      throw new Error(`Bridge identity mismatch: ${key}`);
  }
  if (!actual) throw new Error("Bridge runtime identity missing");
  if (
    expected.sourceIdentity &&
    (!actual.sourceIdentity ||
      actual.sourceIdentity.sha256 !== expected.sourceIdentity.sha256)
  )
    throw new Error("Bridge source identity mismatch");
  return actual;
}
module.exports = {
  prepareProject,
  preflight,
  verifyBridge,
  monitorConsole,
  captureEvidence,
};
