#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import {
  copyFileSync,
  cpSync,
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';

import YAML from 'yaml';

import {
  readStructuredFile,
  resolveApplication,
  sha256Path,
  validateManifest,
  verifyArtifacts,
} from './application-contract.mjs';
import { writeMigrationSplit } from './migration-ownership.mjs';
import { writePlatformAdminConfigSplit } from './config-ownership.mjs';

const SCRIPT_ROOT = dirname(new URL(import.meta.url).pathname);
const DEFAULT_REPO_ROOT = resolve(SCRIPT_ROOT, '../..');

function parseArgs(argv) {
  const options = { repoRoot: DEFAULT_REPO_ROOT };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--repo-root') options.repoRoot = resolve(argv[++index]);
    else if (argument === '--output') options.output = resolve(argv[++index]);
    else throw new Error(`unknown argument: ${argument}`);
  }
  if (!options.output) throw new Error('--output is required');
  return options;
}

function run(command, args, options = {}) {
  try {
    const output = execFileSync(command, args, {
      cwd: options.cwd,
      encoding: 'utf8',
      stdio: options.capture === false ? 'inherit' : ['ignore', 'pipe', 'pipe'],
    });
    return typeof output === 'string' ? output.trim() : '';
  } catch (error) {
    const stderr = typeof error.stderr === 'string' ? error.stderr.trim() : '';
    throw new Error(`${command} ${args.join(' ')} failed${stderr ? `: ${stderr}` : ''}`, { cause: error });
  }
}

function requirePath(path, label) {
  if (!existsSync(path)) throw new Error(`${label} does not exist: ${path}`);
  return path;
}

function copyArtifact(source, target) {
  requirePath(source, 'artifact input');
  mkdirSync(dirname(target), { recursive: true });
  cpSync(source, target, { recursive: true, errorOnExist: true, force: false });
  return target;
}

function sourceRecord(repository, commit) {
  return { repository, commit };
}

function catalogArtifact({ type, id, version, path, output, repository, commit }) {
  return {
    type,
    id,
    version,
    uri: `artifact:${basename(path)}`,
    digest: sha256Path(path),
    localPath: path.slice(output.length + 1),
    source: sourceRecord(repository, commit),
  };
}

function isWebShellPublishableSource(candidate) {
  return !/(?:^|\/)(?:__tests__|tests|test-results|golden-harness|build|node_modules|\.vite)(?:\/|$)/.test(candidate)
    && !/\.(?:test|spec)\.[cm]?[jt]sx?$/.test(candidate)
    && !/\.tsbuildinfo$/.test(candidate);
}

function packPluginSdk(repoRoot, destination) {
  mkdirSync(destination, { recursive: true });
  const packageRoot = mkdtempSync(resolve(tmpdir(), 'auraboot-plugin-sdk-pack-'));
  try {
    run(
      'pnpm',
      [
        'exec',
        'tsc',
        '-p',
        'packages/plugin-sdk/tsconfig.json',
        '--tsBuildInfoFile',
        resolve(packageRoot, 'tsconfig.tsbuildinfo'),
      ],
      { cwd: repoRoot, capture: false },
    );
    const sourceRoot = resolve(repoRoot, 'packages/plugin-sdk');
    cpSync(resolve(sourceRoot, 'dist'), resolve(packageRoot, 'dist'), { recursive: true });
    cpSync(resolve(sourceRoot, 'src'), resolve(packageRoot, 'src'), { recursive: true });
    copyFileSync(resolve(sourceRoot, 'README.md'), resolve(packageRoot, 'README.md'));
    copyFileSync(resolve(repoRoot, 'LICENSE.txt'), resolve(packageRoot, 'LICENSE.txt'));
    const packageManifest = JSON.parse(readFileSync(resolve(sourceRoot, 'package.json'), 'utf8'));
    packageManifest.main = './dist/index.js';
    packageManifest.module = './dist/index.js';
    packageManifest.types = './dist/index.d.ts';
    packageManifest.exports = {
      '.': {
        types: './dist/index.d.ts',
        import: './dist/index.js',
        default: './dist/index.js',
      },
    };
    packageManifest.dependencies = Object.fromEntries(
      Object.entries(packageManifest.dependencies ?? {}).map(([name, range]) => {
        if (!range.startsWith('workspace:')) return [name, range];
        const directory = name === '@auraboot/dsl-types' ? 'dsl-types' : name === '@auraboot/nav-model' ? 'nav-model' : null;
        if (!directory) throw new Error(`unknown plugin SDK workspace dependency: ${name}`);
        const dependencyManifest = JSON.parse(
          readFileSync(resolve(repoRoot, `packages/${directory}/package.json`), 'utf8'),
        );
        return [name, dependencyManifest.version];
      }),
    );
    writeFileSync(resolve(packageRoot, 'package.json'), `${JSON.stringify(packageManifest, null, 2)}\n`);
    const output = run('pnpm', ['pack', '--pack-destination', destination], { cwd: packageRoot });
    const tarball = output.split('\n').at(-1);
    return requirePath(resolve(tarball), 'plugin SDK tarball');
  } finally {
    rmSync(packageRoot, { recursive: true, force: true });
  }
}

function packWebShell(repoRoot, destination, version) {
  const sourceRoot = requirePath(resolve(repoRoot, 'web-admin'), 'Web Shell source');
  const packageRoot = mkdtempSync(resolve(tmpdir(), 'auraboot-web-shell-pack-'));
  try {
    for (const entry of [
      'app',
      'packages',
      'public',
      'scripts',
      'react-router.config.ts',
      'tailwind.config.js',
      'tsconfig.json',
      'vite.config.ts',
      'vitest.config.ts',
      'vitest.setup.ts',
    ]) {
      cpSync(resolve(sourceRoot, entry), resolve(packageRoot, 'web-admin', entry), {
        recursive: true,
        errorOnExist: true,
        force: false,
        filter: isWebShellPublishableSource,
      });
    }
    const webManifest = JSON.parse(readFileSync(resolve(sourceRoot, 'package.json'), 'utf8'));
    webManifest.scripts = {
      build: 'pnpm typecheck && react-router build && pnpm verify:production-react-runtime',
      typecheck: webManifest.scripts.typecheck,
      'verify:production-react-runtime': webManifest.scripts['verify:production-react-runtime'],
    };
    writeFileSync(resolve(packageRoot, 'web-admin/package.json'), `${JSON.stringify(webManifest, null, 2)}\n`);

    cpSync(resolve(repoRoot, 'packages'), resolve(packageRoot, 'packages'), {
      recursive: true,
      filter: (candidate) => isWebShellPublishableSource(candidate)
        && !/(?:^|\/)dist(?:\/|$)/.test(candidate),
    });
    for (const file of [
      'application-cli.mjs',
      'application-contract.mjs',
      'application-graph-adapters.mjs',
    ]) {
      copyArtifact(
        resolve(repoRoot, 'scripts/application', file),
        resolve(packageRoot, 'scripts/application', file),
      );
    }
    for (const file of [
      'application-lock.schema.json',
      'application-manifest.schema.json',
      'web-contribution.schema.json',
    ]) {
      copyArtifact(
        resolve(repoRoot, 'distribution/application', file),
        resolve(packageRoot, 'distribution/application', file),
      );
    }
    copyFileSync(resolve(repoRoot, 'pnpm-lock.yaml'), resolve(packageRoot, 'pnpm-lock.yaml'));
    copyFileSync(resolve(repoRoot, 'LICENSE.txt'), resolve(packageRoot, 'LICENSE.txt'));
    const rootManifest = JSON.parse(readFileSync(resolve(repoRoot, 'package.json'), 'utf8'));
    const workspaceManifest = YAML.parse(readFileSync(resolve(repoRoot, 'pnpm-workspace.yaml'), 'utf8'));
    workspaceManifest.overrides = rootManifest.pnpm?.overrides ?? {};
    writeFileSync(resolve(packageRoot, 'pnpm-workspace.yaml'), YAML.stringify(workspaceManifest));
    writeFileSync(resolve(packageRoot, 'package.json'), `${JSON.stringify({
      name: '@auraboot/web-shell',
      version,
      private: false,
      type: 'module',
      packageManager: rootManifest.packageManager,
      engines: { node: '>=20' },
      devDependencies: rootManifest.devDependencies,
      pnpm: rootManifest.pnpm,
      scripts: { build: 'pnpm --dir web-admin build' },
      files: [
        'LICENSE.txt',
        'pnpm-lock.yaml',
        'pnpm-workspace.yaml',
        'distribution',
        'packages',
        'scripts',
        'web-admin',
      ],
    }, null, 2)}\n`);
    const output = run('pnpm', ['pack', '--pack-destination', destination], { cwd: packageRoot });
    const tarball = output.split('\n').at(-1);
    return requirePath(resolve(tarball), 'Web Shell tarball');
  } finally {
    rmSync(packageRoot, { recursive: true, force: true });
  }
}

function packCompiledPackage(repoRoot, destination, directory, label) {
  mkdirSync(destination, { recursive: true });
  const sourceRoot = resolve(repoRoot, 'packages', directory);
  const packageRoot = mkdtempSync(resolve(tmpdir(), `auraboot-${directory}-pack-`));
  try {
    run(
      'pnpm',
      [
        'exec',
        'tsc',
        '-p',
        `packages/${directory}/tsconfig.json`,
        '--tsBuildInfoFile',
        resolve(packageRoot, 'tsconfig.tsbuildinfo'),
      ],
      { cwd: repoRoot, capture: false },
    );
    cpSync(resolve(sourceRoot, 'dist'), resolve(packageRoot, 'dist'), { recursive: true });
    cpSync(resolve(sourceRoot, 'src'), resolve(packageRoot, 'src'), { recursive: true });
    if (existsSync(resolve(sourceRoot, 'README.md'))) {
      copyFileSync(resolve(sourceRoot, 'README.md'), resolve(packageRoot, 'README.md'));
    }
    copyFileSync(resolve(repoRoot, 'LICENSE.txt'), resolve(packageRoot, 'LICENSE.txt'));
    const packageManifest = JSON.parse(readFileSync(resolve(sourceRoot, 'package.json'), 'utf8'));
    packageManifest.main = './dist/index.js';
    packageManifest.module = './dist/index.js';
    packageManifest.types = './dist/index.d.ts';
    packageManifest.exports = {
      '.': {
        types: './dist/index.d.ts',
        import: './dist/index.js',
        default: './dist/index.js',
      },
    };
    packageManifest.dependencies = Object.fromEntries(
      Object.entries(packageManifest.dependencies ?? {}).map(([name, range]) => {
        if (!range.startsWith('workspace:')) return [name, range];
        const dependencyDirectory = name === '@auraboot/dsl-types' ? 'dsl-types' : null;
        if (!dependencyDirectory) throw new Error(`unknown ${label} workspace dependency: ${name}`);
        const dependencyManifest = JSON.parse(
          readFileSync(resolve(repoRoot, `packages/${dependencyDirectory}/package.json`), 'utf8'),
        );
        return [name, dependencyManifest.version];
      }),
    );
    writeFileSync(resolve(packageRoot, 'package.json'), `${JSON.stringify(packageManifest, null, 2)}\n`);
    const packed = run('pnpm', ['pack', '--pack-destination', destination], { cwd: packageRoot });
    return requirePath(resolve(packed.split('\n').at(-1)), `${label} tarball`);
  } finally {
    rmSync(packageRoot, { recursive: true, force: true });
  }
}

function packUi(repoRoot, destination) {
  mkdirSync(destination, { recursive: true });
  const packageRoot = mkdtempSync(resolve(tmpdir(), 'auraboot-ui-pack-'));
  const sourceRoot = resolve(repoRoot, 'packages/ui');
  try {
    run('pnpm', ['exec', 'tsc', '-p', 'packages/ui/tsconfig.json', '--tsBuildInfoFile', resolve(packageRoot, 'tsconfig.tsbuildinfo')], {
      cwd: repoRoot,
      capture: false,
    });
    cpSync(resolve(sourceRoot, 'dist'), resolve(packageRoot, 'dist'), { recursive: true });
    cpSync(resolve(sourceRoot, 'src'), resolve(packageRoot, 'src'), { recursive: true });
    copyFileSync(resolve(sourceRoot, 'README.md'), resolve(packageRoot, 'README.md'));
    copyFileSync(resolve(repoRoot, 'LICENSE.txt'), resolve(packageRoot, 'LICENSE.txt'));
    const packageManifest = JSON.parse(readFileSync(resolve(sourceRoot, 'package.json'), 'utf8'));
    packageManifest.main = './dist/index.js';
    packageManifest.module = './dist/index.js';
    packageManifest.types = './dist/index.d.ts';
    packageManifest.exports = {
      '.': { types: './dist/index.d.ts', import: './dist/index.js', default: './dist/index.js' },
      './toast': { types: './dist/toast.d.ts', import: './dist/toast.js', default: './dist/toast.js' },
    };
    writeFileSync(resolve(packageRoot, 'package.json'), `${JSON.stringify(packageManifest, null, 2)}\n`);
    const packed = run('pnpm', ['pack', '--pack-destination', destination], { cwd: packageRoot });
    return requirePath(resolve(packed.split('\n').at(-1)), 'UI tarball');
  } finally {
    rmSync(packageRoot, { recursive: true, force: true });
  }
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const { repoRoot, output } = options;
  if (existsSync(output)) throw new Error(`output already exists: ${output}`);

  const dirty = run('git', ['status', '--porcelain'], { cwd: repoRoot });
  if (dirty) throw new Error('refusing to stage artifacts from a dirty Git worktree');

  const commit = run('git', ['rev-parse', 'HEAD'], { cwd: repoRoot });
  const repository = run('git', ['remote', 'get-url', 'origin'], { cwd: repoRoot });
  const version = readFileSync(resolve(repoRoot, 'VERSION'), 'utf8').trim();
  const manifestPath = resolve(repoRoot, 'distribution/application/examples/core-only/app.yaml');
  const manifest = validateManifest(readStructuredFile(manifestPath));
  for (const [name, actual] of Object.entries({
    runtime: manifest.platform.runtime,
    pluginApi: manifest.platform.pluginApi,
    webShell: manifest.platform.webShell,
  })) {
    if (actual !== version) throw new Error(`${name} version ${actual} does not match VERSION ${version}`);
  }

  mkdirSync(output, { recursive: false });
  copyFileSync(manifestPath, resolve(output, 'app.yaml'));

  const runtime = copyArtifact(
    resolve(repoRoot, `platform/build/libs/AuraBoot-${version}-boot.jar`),
    resolve(output, `runtime/AuraBoot-${version}-boot.jar`),
  );
  const pluginApi = copyArtifact(
    resolve(repoRoot, `platform/platform-plugin-api/build/libs/platform-plugin-api-${version}.jar`),
    resolve(output, `maven/platform-plugin-api-${version}.jar`),
  );
  const npmRoot = resolve(output, 'npm');
  const dslTypes = packCompiledPackage(repoRoot, npmRoot, 'dsl-types', 'DSL types');
  const navModel = packCompiledPackage(repoRoot, npmRoot, 'nav-model', 'navigation model');
  const pluginSdk = packPluginSdk(repoRoot, npmRoot);
  const ui = packUi(repoRoot, npmRoot);
  const webShell = packWebShell(repoRoot, npmRoot, version);
  const migrationOutput = resolve(output, 'migrations');
  writeMigrationSplit(
    resolve(repoRoot, 'platform/src/main/resources/db/migration/core'),
    migrationOutput,
  );
  const coreMigrations = requirePath(resolve(migrationOutput, 'core'), 'core-only migrations');
  const coreMeta = copyArtifact(resolve(repoRoot, 'plugins/core-meta'), resolve(output, 'config/core-meta'));
  const orgManagement = copyArtifact(
    resolve(repoRoot, 'plugins/org-management'),
    resolve(output, 'config/org-management'),
  );
  const coreOwnership = copyArtifact(
    resolve(repoRoot, 'plugins/core-ownership'),
    resolve(output, 'config/core-ownership'),
  );
  const configSplit = writePlatformAdminConfigSplit(
    resolve(repoRoot, 'plugins/platform-admin'),
    resolve(output, 'config-split'),
  );
  const platformAdmin = copyArtifact(configSplit.coreRoot, resolve(output, 'config/platform-admin'));

  const artifactInputs = [
    { type: 'runtime', id: 'com.auraboot:runtime', version, path: runtime },
    { type: 'maven', id: 'com.auraboot:platform-plugin-api', version, path: pluginApi },
    { type: 'npm', id: '@auraboot/web-shell', version, path: webShell },
    { type: 'npm', id: '@auraboot/dsl-types', version: '0.0.1', path: dslTypes },
    { type: 'npm', id: '@auraboot/nav-model', version: '0.0.1', path: navModel },
    { type: 'npm', id: '@auraboot/plugin-sdk', version: manifest.platform.pluginSdk, path: pluginSdk },
    { type: 'npm', id: '@auraboot/ui', version: '1.0.0', path: ui },
    { type: 'migration', id: 'core', version, path: coreMigrations },
    { type: 'config', id: 'core-meta', version, path: coreMeta },
    { type: 'config', id: 'platform-admin', version, path: platformAdmin },
    { type: 'config', id: 'org-management', version, path: orgManagement },
    { type: 'config', id: 'core-ownership', version, path: coreOwnership },
  ];
  const catalog = {
    schemaVersion: 1,
    artifacts: artifactInputs.map((artifact) => catalogArtifact({
      ...artifact,
      output,
      repository,
      commit,
    })),
  };
  const lock = resolveApplication(manifest, catalog);
  verifyArtifacts(lock, { artifactRoot: output });
  writeFileSync(resolve(output, 'artifact-catalog.json'), `${JSON.stringify(catalog, null, 2)}\n`);
  writeFileSync(resolve(output, 'application.lock'), `${JSON.stringify(lock, null, 2)}\n`);
  writeFileSync(resolve(output, 'app.resolved.yaml'), YAML.stringify(manifest));
  writeFileSync(
    resolve(output, 'release-receipt.json'),
    `${JSON.stringify({
      schemaVersion: 1,
      source: sourceRecord(repository, commit),
      application: lock.application,
      manifestDigest: lock.manifestDigest,
      lockIdentity: lock.identity,
      graphDigest: lock.composition.graphDigest,
      verification: { resolver: 'application-contract-v1', checksum: 'sha256', result: 'PASS' },
      artifacts: lock.artifacts.map(({ type, id, version: artifactVersion, digest, localPath }) => ({
        type,
        id,
        version: artifactVersion,
        digest,
        localPath,
      })),
    }, null, 2)}\n`,
  );
  writeFileSync(
    resolve(output, 'staging-summary.json'),
    `${JSON.stringify({
      schemaVersion: 1,
      source: sourceRecord(repository, commit),
      application: lock.application,
      lockIdentity: lock.identity,
      graphDigest: lock.composition.graphDigest,
      artifactCount: lock.artifacts.length,
      catalogArtifactCount: catalog.artifacts.length,
    }, null, 2)}\n`,
  );
  process.stdout.write(`${JSON.stringify({
    status: 'PASS',
    output,
    commit,
    lockIdentity: lock.identity,
    graphDigest: lock.composition.graphDigest,
    artifactCount: lock.artifacts.length,
    catalogArtifactCount: catalog.artifacts.length,
  }, null, 2)}\n`);
}

try {
  main();
} catch (error) {
  process.stderr.write(`core-only artifact staging failed: ${error.message}\n`);
  process.exitCode = 1;
}
