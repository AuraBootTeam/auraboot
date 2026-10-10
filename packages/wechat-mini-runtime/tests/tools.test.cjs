const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { EventEmitter } = require("node:events");
const tools = require("../tools/index.cjs");
function bridge(config) {
  const mp = new EventEmitter();
  mp.evaluate = async () => config;
  mp.currentPage = async () => ({ path: "pages/login/index" });
  mp.disconnect = async () => {
    mp.disconnected = true;
  };
  return mp;
}
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "aura-mini-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const source = path.join(root, "source");
  fs.mkdirSync(source);
  fs.writeFileSync(path.join(source, "config.js"), "original");
  fs.writeFileSync(path.join(source, "app.json"), "{}");
  fs.writeFileSync(path.join(source, "project.config.json"), "{}");
  return {
    root,
    source,
    out: path.join(root, "generated"),
    config: {
      apiBaseUrl: "http://127.0.0.1:6200",
      version: "1.0",
      appEnv: "verification",
      target: "simulator",
      runtimeName: "fixture-test",
    },
    identity: { sourceRevision: "fixture" },
    mode: "fixture",
  };
}
test("generated project is separate, excludes dependencies and records explicit data mode", (t) => {
  const f = fixture(t);
  fs.mkdirSync(path.join(f.source, "node_modules"));
  fs.writeFileSync(path.join(f.source, "node_modules", "secret"), "secret");
  const manifest = tools.prepareProject(f);
  assert.equal(manifest.mode, "fixture");
  assert.equal(
    fs.readFileSync(path.join(f.source, "config.js"), "utf8"),
    "original",
  );
  assert.equal(fs.existsSync(path.join(f.out, "node_modules")), false);
  assert.match(
    fs.readFileSync(path.join(f.out, "config.js"), "utf8"),
    /fixture-test/,
  );
  tools.prepareProject(f);
});
test("generator rejects foreign output, source overlap and production targets", (t) => {
  const f = fixture(t);
  fs.mkdirSync(f.out);
  fs.writeFileSync(path.join(f.out, "owned"), "keep");
  assert.throws(() => tools.prepareProject(f), /unowned/);
  assert.equal(fs.readFileSync(path.join(f.out, "owned"), "utf8"), "keep");
  assert.throws(
    () => tools.prepareProject({ ...f, out: f.source }),
    /separate/,
  );
  assert.throws(
    () =>
      tools.prepareProject({
        ...f,
        config: { ...f.config, appEnv: "production" },
      }),
    /production/,
  );
});
test("invalid source symlinks cannot destroy an existing owned project", (t) => {
  const f = fixture(t);
  tools.prepareProject(f);
  fs.writeFileSync(path.join(f.out, "preserved"), "previous generated project");
  fs.symlinkSync(
    path.join(f.source, "config.js"),
    path.join(f.source, "linked.js"),
  );
  assert.throws(() => tools.prepareProject(f), /symlinks/);
  assert.equal(
    fs.readFileSync(path.join(f.out, "preserved"), "utf8"),
    "previous generated project",
  );
  assert.equal(
    fs.existsSync(path.join(f.out, ".aura-mini-project.json")),
    true,
  );
});
test("console gate rejects runtime errors, keeps redacted evidence and needs an explicit allowlist", () => {
  const mp = new EventEmitter();
  const monitor = tools.monitorConsole(mp);
  mp.emit("console", {
    type: "error",
    args: ["TypeError: phone=13812345678", { token: "unsafe" }],
  });
  assert.throws(() => monitor.assertClean(), /1 errors/);
  assert.doesNotMatch(JSON.stringify(monitor.records), /13812345678|unsafe/);
  monitor.detach();
  const allowed = tools.monitorConsole(mp, {
    allow: (msg) => msg.args[0] === "expected-native-denial",
  });
  mp.emit("console", { type: "error", args: ["expected-native-denial"] });
  allowed.assertClean();
});
test("preflight requires a real CLI login confirmation", (t) => {
  const f = fixture(t);
  assert.throws(
    () =>
      tools.preflight({
        cli: "fixture",
        project: f.source,
        port: 9420,
        execute: () => '{"login":false}',
      }),
    /not confirmed/,
  );
  assert.equal(
    tools.preflight({
      cli: "fixture",
      project: f.source,
      port: 9420,
      execute: () => '{"login":true}',
    }).loggedIn,
    true,
  );
});
test("DevTools rejects source/release/configuration mismatches before launching or fixtures", async (t) => {
  const f = fixture(t);
  const calls = [];
  const args = {
    ...f,
    project: f.source,
    port: 9464,
    cli: "fixture",
    execute: (...args) => {
      calls.push(args);
      return '{"login":true}';
    },
  };
  await assert.rejects(tools.openDevTools(args), /Independent generated/);
  tools.prepareProject(f);
  await assert.rejects(
    tools.openDevTools({ ...args, project: f.out, mode: "real-stack" }),
    /data mode/,
  );
  await assert.rejects(
    tools.openDevTools({
      ...args,
      project: f.out,
      expected: { apiBaseUrl: "http://127.0.0.1:6201" },
    }),
    /configuration mismatch/,
  );
  assert.equal(calls.length, 0);
  tools.prepareProject({
    ...f,
    purpose: "release-build",
    config: { ...f.config, appEnv: "production" },
  });
  await assert.rejects(
    tools.openDevTools({ ...args, project: f.out }),
    /release project/,
  );
});
test("DevTools retries refused startup connections only and never accepts a different live app", async (t) => {
  const f = fixture(t);
  const manifest = tools.prepareProject(f);
  const mp = bridge(manifest.config),
    calls = [];
  let connections = 0;
  const args = {
    project: f.out,
    port: 9464,
    cli: "fixture",
    mode: "fixture",
    intervalMs: 1,
    isPortBusy: async () => false,
    execute: (_, args) => {
      calls.push(args[0]);
      return '{"login":true}';
    },
    automator: {
      connect: async () => {
        if (++connections === 1) throw new Error("ECONNREFUSED");
        if (connections === 2)
          throw new Error(
            "Failed connecting to ws://127.0.0.1:9464, check if target project window is opened with automation enabled",
          );
        return mp;
      },
    },
  };
  const driver = await tools.openDevTools(args);
  assert.deepEqual(calls, ["islogin", "auto"]);
  assert.equal(connections, 3);
  mp.emit("exception", { token: "private" });
  await assert.rejects(driver.close(), /console rejected/);
  assert.equal(mp.disconnected, true);
  assert.equal(mp.listenerCount("exception"), 0);
  const wrong = bridge({ ...manifest.config, runtimeName: "another-task" });
  await assert.rejects(
    tools.openDevTools({
      ...args,
      isPortBusy: async () => true,
      automator: { connect: async () => wrong },
    }),
    /identity mismatch/,
  );
  assert.equal(wrong.disconnected, true);
  let failures = 0;
  await assert.rejects(
    tools.openDevTools({
      ...args,
      isPortBusy: async () => true,
      automator: {
        connect: async () => {
          failures++;
          throw new Error("Compiler dependency missing");
        },
      },
    }),
    /Compiler dependency/,
  );
  assert.equal(failures, 1);
});
test("a timed-out DevTools connection is disposed if it eventually completes", async (t) => {
  const f = fixture(t),
    manifest = tools.prepareProject(f),
    mp = bridge(manifest.config);
  let resolve;
  const connection = new Promise((done) => {
    resolve = done;
  });
  await assert.rejects(
    tools.openDevTools({
      project: f.out,
      port: 9464,
      cli: "fixture",
      timeoutMs: 5,
      execute: () => '{"login":true}',
      isPortBusy: async () => true,
      automator: { connect: () => connection },
    }),
    /timed out/,
  );
  resolve(mp);
  await new Promise((done) => setImmediate(done));
  assert.equal(mp.disconnected, true);
});
test("a hung appservice identity check fails and disconnects its bridge", async (t) => {
  const f = fixture(t),
    manifest = tools.prepareProject(f),
    mp = bridge(manifest.config);
  mp.evaluate = () => new Promise(() => {});
  await assert.rejects(
    tools.openDevTools({
      project: f.out,
      port: 9464,
      cli: "fixture",
      timeoutMs: 5,
      execute: () => '{"login":true}',
      isPortBusy: async () => true,
      automator: { connect: async () => mp },
    }),
    /identity check timed out/,
  );
  assert.equal(mp.disconnected, true);
  assert.equal(mp.listenerCount("console"), 0);
});
test("DevTools waits for initial app identity and page readiness without rerunning the app", async (t) => {
  const f = fixture(t),
    manifest = tools.prepareProject(f),
    mp = bridge(manifest.config);
  let reads = 0,
    pages = 0,
    connects = 0;
  mp.evaluate = async () => (++reads === 1 ? undefined : manifest.config);
  mp.currentPage = async () => {
    pages++;
    if (pages === 1)
      throw new Error(
        "Cannot destructure property 'rawPath' of 't.getPageMetaByWebviewId(...)' as it is null.",
      );
    return pages === 2 ? null : { path: "pages/login/index" };
  };
  const driver = await tools.openDevTools({
    project: f.out,
    port: 9464,
    cli: "fixture",
    intervalMs: 1,
    execute: () => '{"login":true}',
    isPortBusy: async () => true,
    automator: {
      connect: async () => {
        connects++;
        return mp;
      },
    },
  });
  assert.equal(connects, 1);
  assert.equal(reads, 4);
  assert.equal(pages, 3);
  await driver.close();
});
test("screenshots remain captured pending visual review and console errors block verdict", async (t) => {
  const f = fixture(t);
  const mp = new EventEmitter();
  mp.screenshot = async ({ path: filename }) =>
    fs.writeFileSync(filename, "fixture image bytes");
  const monitor = tools.monitorConsole(mp);
  const args = {
    directory: f.out,
    scenario: "S01",
    identity: f.identity,
    mode: "fixture",
    monitor,
  };
  const record = await tools.captureEvidence(mp, args);
  assert.equal(record.visualReview, "pending");
  assert.equal(record.verdict, "captured");
  assert.match(record.sha256, /^[a-f0-9]{64}$/);
  mp.emit("console", { type: "error", args: ["TypeError"] });
  await assert.rejects(
    tools.captureEvidence(mp, { ...args, scenario: "S02" }),
    /console rejected/,
  );
});

test("bridge identity rejects a reachable wrong project or missing appservice", async () => {
  const expected = {
    apiBaseUrl: "http://127.0.0.1:6200",
    runtimeName: "fixture-test",
    target: "simulator",
    version: "1.0",
    dataMode: "fixture",
    sourceIdentity: { sha256: "source-digest" },
  };
  await tools.verifyBridge(
    { evaluate: async () => ({ ...expected }) },
    expected,
  );
  await assert.rejects(
    tools.verifyBridge(
      { evaluate: async () => ({ ...expected, runtimeName: "other-owner" }) },
      expected,
    ),
    /identity mismatch/,
  );
  await assert.rejects(
    tools.verifyBridge({ evaluate: async () => undefined }, expected),
    /identity missing/,
  );
  await assert.rejects(
    tools.verifyBridge(
      {
        evaluate: async () => ({
          ...expected,
          sourceIdentity: { sha256: "other" },
        }),
      },
      expected,
    ),
    /source identity mismatch/,
  );
});

test("generator resolves output parent symlinks before checking source overlap", (t) => {
  const f = fixture(t);
  const alias = path.join(f.root, "source-alias");
  fs.symlinkSync(f.source, alias);
  assert.throws(
    () =>
      tools.prepareProject({ ...f, out: path.join(alias, "nested-output") }),
    /separate/,
  );
  assert.equal(fs.existsSync(path.join(f.source, "nested-output")), false);
});

test("console gate captures exception events, bounds flood memory and detaches both listeners", () => {
  const mp = new EventEmitter();
  const monitor = tools.monitorConsole(mp, { limit: 2 });
  for (let i = 0; i < 5; i++)
    mp.emit("exception", { message: "private-user-value", token: "secret" });
  assert.equal(monitor.records.length, 2);
  assert.equal(monitor.errors.length, 2);
  assert.doesNotMatch(
    JSON.stringify(monitor.records),
    /private-user-value|secret/,
  );
  assert.throws(() => monitor.assertClean(), /overflow=true/);
  monitor.detach();
  assert.equal(mp.listenerCount("console"), 0);
  assert.equal(mp.listenerCount("exception"), 0);
});
test("route waiting reacquires the top page and rejects a missing transition", async () => {
  let calls = 0;
  const mp = {
    currentPage: async () => ({
      path: ++calls < 3 ? "pages/list/index" : "pages/detail/index",
    }),
  };
  assert.equal(
    (await tools.waitForPage(mp, "/pages/detail/index", { intervalMs: 1 }))
      .path,
    "pages/detail/index",
  );
  await assert.rejects(
    tools.waitForPage(
      { currentPage: async () => ({ path: "pages/list/index" }) },
      "pages/detail/index",
      { timeoutMs: 2, intervalMs: 1 },
    ),
    /transition timed out/,
  );
});
