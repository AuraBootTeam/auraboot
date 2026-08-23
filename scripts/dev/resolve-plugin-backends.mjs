#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

function fail(message) {
  process.stderr.write(`ERROR: ${message}\n`);
  process.exitCode = 2;
}

function parseArgs(argv) {
  const args = {
    repoRoot: "",
    profile: "none",
    extraPluginRoots: [],
    plugins: [],
    format: "tsv",
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = () => {
      index += 1;
      if (index >= argv.length) throw new Error(`${arg} requires a value`);
      return argv[index];
    };

    if (arg === "--repo-root") args.repoRoot = next();
    else if (arg.startsWith("--repo-root="))
      args.repoRoot = arg.slice("--repo-root=".length);
    else if (arg === "--profile") args.profile = next();
    else if (arg.startsWith("--profile="))
      args.profile = arg.slice("--profile=".length);
    else if (arg === "--extra-plugin-root") args.extraPluginRoots.push(next());
    else if (arg.startsWith("--extra-plugin-root=")) {
      args.extraPluginRoots.push(arg.slice("--extra-plugin-root=".length));
    } else if (arg === "--plugin") args.plugins.push(next());
    else if (arg.startsWith("--plugin="))
      args.plugins.push(arg.slice("--plugin=".length));
    else if (arg === "--format") args.format = next();
    else if (arg.startsWith("--format="))
      args.format = arg.slice("--format=".length);
    else throw new Error(`unknown argument: ${arg}`);
  }

  if (!args.repoRoot) throw new Error("--repo-root is required");
  if (!["json", "tsv"].includes(args.format))
    throw new Error("--format must be json or tsv");
  return args;
}

function readJson(file, description) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    throw new Error(`${description} is invalid: ${file}: ${error.message}`);
  }
}

function selectedPlugins(repoRoot, profile, explicitPlugins) {
  const selected = [];
  if (profile && profile !== "none") {
    const profilesPath = path.join(
      repoRoot,
      "scripts/dev/plugin-import-profiles.json",
    );
    const profiles = readJson(profilesPath, "plugin import profiles");
    const configured = profiles[profile];
    if (
      !Array.isArray(configured) ||
      configured.some((name) => typeof name !== "string" || !name)
    ) {
      throw new Error(`unknown or invalid plugin profile: ${profile}`);
    }
    selected.push(...configured);
  }
  selected.push(...explicitPlugins);
  const unique = [...new Set(selected)];
  for (const pluginName of unique) {
    if (!/^[A-Za-z0-9._-]+$/.test(pluginName)) {
      throw new Error(`unsafe plugin name: ${pluginName}`);
    }
  }
  return unique;
}

function resolvePluginDir(roots, pluginName) {
  for (const root of roots) {
    const pluginDir = path.join(root, pluginName);
    if (fs.existsSync(path.join(pluginDir, "plugin.json"))) return pluginDir;
  }
  return null;
}

function backendSpec(pluginName, pluginDir) {
  const manifestPath = path.join(pluginDir, "plugin.json");
  const manifest = readJson(manifestPath, `plugin manifest for ${pluginName}`);
  if (manifest.backend == null) return null;

  const jarRelativePath = manifest.backend?.jarPath;
  if (typeof jarRelativePath !== "string" || jarRelativePath.length === 0) {
    throw new Error(
      `plugin ${pluginName} declares backend without backend.jarPath`,
    );
  }

  const normalizedRelativePath = path.normalize(jarRelativePath);
  const buildMarker = `${path.sep}build${path.sep}`;
  const buildMarkerIndex = normalizedRelativePath.indexOf(buildMarker);
  if (
    path.isAbsolute(jarRelativePath) ||
    normalizedRelativePath.startsWith(`..${path.sep}`)
  ) {
    throw new Error(
      `plugin ${pluginName} backend.jarPath escapes its plugin directory`,
    );
  }
  if (buildMarkerIndex <= 0) {
    throw new Error(
      `plugin ${pluginName} backend.jarPath must contain <backend>/build/`,
    );
  }

  const jarPath = path.resolve(pluginDir, normalizedRelativePath);
  const relativeJarPath = path.relative(pluginDir, jarPath);
  if (relativeJarPath.startsWith(`..${path.sep}`) || relativeJarPath === "..") {
    throw new Error(
      `plugin ${pluginName} backend.jarPath escapes its plugin directory`,
    );
  }

  return {
    plugin: pluginName,
    pluginDir,
    buildDir: path.resolve(
      pluginDir,
      normalizedRelativePath.slice(0, buildMarkerIndex),
    ),
    jarPath,
  };
}

export function resolvePluginBackends({
  repoRoot,
  profile,
  extraPluginRoots,
  plugins,
}) {
  const resolvedRepoRoot = path.resolve(repoRoot);
  const roots = [
    path.join(resolvedRepoRoot, "plugins"),
    ...extraPluginRoots.map((root) => path.resolve(root)),
  ];
  const selected = selectedPlugins(resolvedRepoRoot, profile, plugins);
  const backends = [];

  for (const pluginName of selected) {
    const pluginDir = resolvePluginDir(roots, pluginName);
    if (!pluginDir) continue;
    const spec = backendSpec(pluginName, pluginDir);
    if (spec) backends.push(spec);
  }
  return backends;
}

function renderTsv(backends) {
  return backends
    .map(({ plugin, pluginDir, buildDir, jarPath }) =>
      [plugin, pluginDir, buildDir, jarPath].join("\t"),
    )
    .join("\n");
}

function main() {
  try {
    const args = parseArgs(process.argv.slice(2));
    const backends = resolvePluginBackends(args);
    process.stdout.write(
      args.format === "json"
        ? `${JSON.stringify(backends, null, 2)}\n`
        : `${renderTsv(backends)}${backends.length ? "\n" : ""}`,
    );
  } catch (error) {
    fail(error.message);
  }
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
)
  main();
