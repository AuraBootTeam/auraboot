import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { resolvePluginBackends } from "./resolve-plugin-backends.mjs";

const resolverCli = fileURLToPath(
  new URL("./resolve-plugin-backends.mjs", import.meta.url),
);

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function addPlugin(root, name, manifest) {
  const backend = manifest.backend == null
    ? undefined
    : { entryClass: `com.auraboot.${name}.Plugin`, ...manifest.backend };
  writeJson(path.join(root, name, "plugin.json"), {
    pluginId: `com.auraboot.${name}`,
    pluginType: "config",
    ...manifest,
    ...(backend == null ? {} : { backend }),
  });
}

function fixture(t) {
  const workspace = fs.mkdtempSync(
    path.join(os.tmpdir(), "aura-plugin-backends-"),
  );
  const repoRoot = path.join(workspace, "core");
  const extraRoot = path.join(workspace, "plugins");
  writeJson(path.join(repoRoot, "scripts/dev/plugin-import-profiles.json"), {
    crm: [
      "org-management",
      "product-catalog",
      "crm",
      "inventory",
      "finance",
      "sales",
    ],
  });
  t.after(() => fs.rmSync(workspace, { recursive: true, force: true }));
  return { repoRoot, extraRoot };
}

test("resolves backend.jarPath across explicit roots in selected dependency order", (t) => {
  const { repoRoot, extraRoot } = fixture(t);
  addPlugin(path.join(repoRoot, "plugins"), "org-management", {});
  addPlugin(path.join(repoRoot, "plugins"), "crm", {
    pluginType: "hybrid",
    backend: { jarPath: "backend/build/libs/crm-plugin.jar" },
  });
  addPlugin(extraRoot, "product-catalog", {
    pluginType: "hybrid",
    backend: { jarPath: "backend/build/libs/product-catalog-plugin.jar" },
  });
  addPlugin(extraRoot, "inventory", {
    pluginType: "config",
    backend: { jarPath: "backend/build/libs/inventory-plugin.jar" },
  });
  addPlugin(extraRoot, "finance", {
    pluginType: "hybrid",
    backend: { jarPath: "backend/build/libs/finance-plugin.jar" },
  });
  addPlugin(extraRoot, "sales", {
    pluginType: "hybrid",
    backend: { jarPath: "backend/build/libs/sales-plugin.jar" },
  });

  const result = resolvePluginBackends({
    repoRoot,
    profile: "crm",
    extraPluginRoots: [extraRoot],
    plugins: [],
  });

  assert.deepEqual(
    result.map(({ plugin }) => plugin),
    ["product-catalog", "crm", "inventory", "finance", "sales"],
  );
  assert.equal(
    result.find(({ plugin }) => plugin === "inventory").pluginDir,
    path.join(extraRoot, "inventory"),
  );
  assert.equal(
    result.find(({ plugin }) => plugin === "inventory").buildDir,
    path.join(extraRoot, "inventory/backend"),
  );
});

test("does not guess an independent plugin root when none is explicitly provided", (t) => {
  const { repoRoot, extraRoot } = fixture(t);
  addPlugin(extraRoot, "product-catalog", {
    pluginType: "hybrid",
    backend: { jarPath: "backend/build/libs/product-catalog-plugin.jar" },
  });

  const result = resolvePluginBackends({
    repoRoot,
    profile: "none",
    extraPluginRoots: [],
    plugins: ["product-catalog"],
  });

  assert.deepEqual(result, []);
});

test("CLI accepts an explicit independent root and emits a shell-safe TSV contract", (t) => {
  const { repoRoot, extraRoot } = fixture(t);
  addPlugin(extraRoot, "inventory", {
    pluginType: "config",
    backend: { jarPath: "backend/build/libs/inventory-plugin.jar" },
  });

  const result = spawnSync(
    process.execPath,
    [
      resolverCli,
      "--repo-root",
      repoRoot,
      "--extra-plugin-root",
      extraRoot,
      "--profile",
      "none",
      "--plugin",
      "inventory",
      "--format",
      "tsv",
    ],
    { encoding: "utf8" },
  );

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.stdout.trim().split("\t"), [
    "inventory",
    path.join(extraRoot, "inventory"),
    path.join(extraRoot, "inventory/backend"),
    path.join(extraRoot, "inventory/backend/build/libs/inventory-plugin.jar"),
    "com.auraboot.inventory.Plugin",
  ]);
});

test("keeps OSS root precedence and de-duplicates profile plus explicit selections", (t) => {
  const { repoRoot, extraRoot } = fixture(t);
  addPlugin(path.join(repoRoot, "plugins"), "crm", {
    pluginType: "hybrid",
    backend: { jarPath: "backend/build/libs/oss-crm-plugin.jar" },
  });
  addPlugin(extraRoot, "crm", {
    pluginType: "hybrid",
    backend: { jarPath: "backend/build/libs/external-crm-plugin.jar" },
  });

  const result = resolvePluginBackends({
    repoRoot,
    profile: "none",
    extraPluginRoots: [extraRoot],
    plugins: ["crm", "crm"],
  });

  assert.equal(result.length, 1);
  assert.equal(result[0].pluginDir, path.join(repoRoot, "plugins/crm"));
  assert.match(result[0].jarPath, /oss-crm-plugin\.jar$/);
});

test("fails closed when a backend jar path escapes or lacks a build boundary", (t) => {
  const { repoRoot } = fixture(t);
  const ossRoot = path.join(repoRoot, "plugins");
  addPlugin(ossRoot, "escape", {
    backend: { jarPath: "../outside.jar" },
  });
  addPlugin(ossRoot, "unbuildable", {
    backend: { jarPath: "backend/libs/plugin.jar" },
  });

  assert.throws(
    () =>
      resolvePluginBackends({
        repoRoot,
        profile: "none",
        extraPluginRoots: [],
        plugins: ["escape"],
      }),
    /escapes its plugin directory/,
  );
  assert.throws(
    () =>
      resolvePluginBackends({
        repoRoot,
        profile: "none",
        extraPluginRoots: [],
        plugins: ["unbuildable"],
      }),
    /must contain <backend>\/build\//,
  );
});

test("fails closed when a backend omits its PF4J entry class", (t) => {
  const { repoRoot } = fixture(t);
  writeJson(path.join(repoRoot, "plugins/missing-entry/plugin.json"), {
    pluginId: "com.auraboot.missing-entry",
    pluginType: "config",
    backend: { jarPath: "backend/build/libs/plugin.jar" },
  });

  assert.throws(
    () =>
      resolvePluginBackends({
        repoRoot,
        profile: "none",
        extraPluginRoots: [],
        plugins: ["missing-entry"],
      }),
    /without backend\.entryClass/,
  );
});
