const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { execFileSync } = require("node:child_process");
const { createServer } = require("node:net");
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
  const excluded = new Set([
    "node_modules",
    "tests",
    "golden",
    ".git",
    ".DS_Store",
  ]);
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
  fs.cpSync(source, out, {
    recursive: true,
    filter: (entry) =>
      !path
        .relative(source, entry)
        .split(path.sep)
        .some((part) => excluded.has(part)),
  });
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
    if (failure && !entry.allowed) {
      if (errors.length < limit) errors.push(entry);
      else overflow = true;
    }
  };
  const exceptionHandler = (value) => handler({ type: "error", args: [value] });
  mp.on("console", handler);
  mp.on("exception", exceptionHandler);
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
      if (mp.off) {
        mp.off("console", handler);
        mp.off("exception", exceptionHandler);
      }
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
// A native tap resolves before route transition finishes. Reacquire the top page.
async function waitForPage(
  mp,
  route,
  { timeoutMs = 15000, intervalMs = 100, monitor } = {},
) {
  const deadline = Date.now() + timeoutMs;
  do {
    if (monitor) monitor.assertClean();
    const page = await mp.currentPage();
    if (page && page.path === route.replace(/^\//, "")) return page;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  } while (Date.now() < deadline);
  throw new Error(`Mini page transition timed out: ${route}`);
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
function readProject(project, { expected = {}, mode } = {}) {
  project = fs.realpathSync(project);
  const marker = path.join(project, MARKER);
  if (!fs.existsSync(marker))
    throw new Error("Independent generated project required");
  const manifest = JSON.parse(fs.readFileSync(marker, "utf8"));
  if (
    manifest.kind !== "aura-mini-test" ||
    fs.realpathSync(manifest.out) !== project
  )
    throw new Error("Generated project ownership mismatch");
  if (
    manifest.purpose !== "test" ||
    /^(online|production)$/.test(manifest.config.appEnv)
  )
    throw new Error("DevTools test cannot target a release project");
  if (mode && manifest.mode !== mode)
    throw new Error("Project data mode mismatch");
  for (const [key, value] of Object.entries(expected)) {
    if (value !== undefined && manifest.config[key] !== value)
      throw new Error(`Project configuration mismatch: ${key}`);
  }
  target(manifest.config);
  return manifest;
}
function portBusy(port) {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", (error) =>
      error.code === "EADDRINUSE" ? resolve(true) : reject(error),
    );
    server.once("listening", () => server.close(() => resolve(false)));
    server.listen(port, "127.0.0.1");
  });
}
async function openDevTools({
  automator,
  cli,
  project,
  port,
  expected,
  mode,
  execute = execFileSync,
  isPortBusy = portBusy,
  timeoutMs = 30000,
  intervalMs = 100,
}) {
  // Validate the independent project's identity before any product fixture writes.
  const manifest = readProject(project, { expected, mode });
  preflight({ cli, project, port, execute });
  if (!(await isPortBusy(port)))
    execute(
      cli,
      ["auto", "--project", manifest.out, "--auto-port", String(port)],
      {
        encoding: "utf8",
        timeout: 20000,
      },
    );
  const deadline = Date.now() + timeoutMs;
  let mp;
  do {
    let timer;
    const connection = automator.connect({
      wsEndpoint: `ws://127.0.0.1:${port}`,
    });
    try {
      mp = await Promise.race([
        connection,
        new Promise((_, reject) => {
          timer = setTimeout(
            () => reject(new Error("DevTools connection timed out")),
            Math.max(1, deadline - Date.now()),
          );
        }),
      ]);
      break;
    } catch (error) {
      // Dispose any connection which completes after the bounded observation.
      connection
        .then(
          (late) => late.disconnect(),
          () => {},
        )
        .catch(() => {});
      const wrappedTransportFailure =
        error.message ===
        `Failed connecting to ws://127.0.0.1:${port}, check if target project window is opened with automation enabled`;
      if (
        Date.now() >= deadline ||
        (!wrappedTransportFailure &&
          !/ECONNREFUSED|Connection closed|socket hang up/i.test(
            error.message || "",
          ))
      )
        throw error;
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
    } finally {
      clearTimeout(timer);
    }
  } while (Date.now() < deadline);
  if (!mp) throw new Error("DevTools bridge unavailable");
  const monitor = monitorConsole(mp);
  let identityTimer;
  try {
    await Promise.race([
      (async () => {
        do {
          monitor.assertClean();
          let initialized = true;
          try {
            await verifyBridge(mp, manifest.config);
          } catch (error) {
            if (error.message !== "Bridge runtime identity missing")
              throw error;
            initialized = false;
          }
          if (initialized) {
            try {
              if (await mp.currentPage()) return;
            } catch (error) {
              // DevTools 2.02 / automator 0.12: initial webview metadata is not registered yet.
              if (
                error.message !==
                "Cannot destructure property 'rawPath' of 't.getPageMetaByWebviewId(...)' as it is null."
              )
                throw error;
              monitor.assertClean();
            }
          }
          await new Promise((resolve) => setTimeout(resolve, intervalMs));
        } while (Date.now() < deadline);
        throw new Error("DevTools appservice did not initialize");
      })(),
      new Promise((_, reject) => {
        identityTimer = setTimeout(
          () => reject(new Error("DevTools identity check timed out")),
          Math.max(1, deadline - Date.now()),
        );
      }),
    ]);
    monitor.assertClean();
  } catch (error) {
    monitor.detach();
    await mp.disconnect();
    throw error;
  } finally {
    clearTimeout(identityTimer);
  }
  return {
    mp,
    monitor,
    manifest,
    capture(filename, directory) {
      if (!/^[a-z0-9_-]+\.png$/i.test(filename))
        throw new Error("Safe screenshot filename required");
      return captureEvidence(mp, {
        directory,
        scenario: filename.slice(0, -4),
        identity: manifest.identity,
        mode: manifest.mode,
        monitor,
      });
    },
    async close() {
      try {
        monitor.assertClean();
      } finally {
        monitor.detach();
        await mp.disconnect();
      }
    },
  };
}
module.exports = {
  prepareProject,
  preflight,
  verifyBridge,
  waitForPage,
  monitorConsole,
  captureEvidence,
  readProject,
  openDevTools,
};
