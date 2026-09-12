import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';

const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const LINUX_JRE_BASE = 'eclipse-temurin:21-jre@sha256:a80c51f2d09a3e7e00d521f1c817bbceb6b3be94109b4a784d46078099882dda';

function commandExists(command) {
  return spawnSync('command', ['-v', command], { shell: true, stdio: 'ignore' }).status === 0;
}

function createRunnableLayout({ output, rootfs, labels, entrypoint }) {
  const context = mkdtempSync(resolve(tmpdir(), 'auraboot-image-'));
  const archive = resolve(context, 'image.tar');
  const contextRootfs = resolve(context, 'rootfs');
  const tag = `aura-release-${process.pid}-${digest(Buffer.from(JSON.stringify(labels))).slice(0, 12)}:local`;
  try {
    cpSync(rootfs, contextRootfs, { recursive: true });
    writeFileSync(resolve(context, 'Dockerfile'), [
      `FROM ${LINUX_JRE_BASE}`,
      'COPY rootfs/ /',
      `ENTRYPOINT ${JSON.stringify(entrypoint)}`,
      '',
    ].join('\n'));

    const labelArgs = Object.entries(labels).flatMap(([key, value]) => ['--label', `${key}=${value}`]);
    const builder = process.env.AURA_OCI_BUILDER
      ?? (commandExists('container') ? 'container' : commandExists('docker') ? 'docker' : '');
    if (builder === 'container') {
      execFileSync('container', ['build', '--progress', 'plain', '--tag', tag, ...labelArgs, context], { stdio: 'inherit' });
      execFileSync('container', ['image', 'save', '--output', archive, tag], { stdio: 'inherit' });
    } else if (builder === 'docker') {
      execFileSync('docker', [
        'buildx', 'build', '--progress', 'plain',
        '--platform', `linux/${process.arch === 'arm64' ? 'arm64' : 'amd64'}`,
        '--output', `type=oci,dest=${archive}`,
        ...labelArgs,
        context,
      ], { stdio: 'inherit' });
    } else {
      throw new Error('a release OCI image requires Apple container or Docker buildx');
    }

    rmSync(output, { recursive: true, force: true });
    mkdirSync(output, { recursive: true });
    execFileSync('tar', ['-xf', archive, '-C', output]);
    const index = JSON.parse(readFileSync(resolve(output, 'index.json'), 'utf8'));
    const imageDigest = index.manifests?.[0]?.digest;
    if (!/^sha256:[0-9a-f]{64}$/.test(imageDigest ?? '')) {
      throw new Error('OCI builder did not produce a digest-addressed image manifest');
    }
    return imageDigest;
  } finally {
    if (commandExists('container')) {
      spawnSync('container', ['image', 'rm', tag], { stdio: 'ignore' });
    }
    rmSync(context, { recursive: true, force: true });
  }
}

/** Create a standards-compliant single-platform OCI image layout from a prepared rootfs. */
export function createOciImageLayout({ output, rootfs, labels = {}, entrypoint = [] }) {
  return createRunnableLayout({ output, rootfs, labels, entrypoint });
}

/** Prepare a rootfs using explicit source-to-destination mappings. */
export function prepareRootfs(mappings) {
  const rootfs = mkdtempSync(resolve(tmpdir(), 'auraboot-rootfs-'));
  for (const { source, destination } of mappings) {
    const target = resolve(rootfs, destination.replace(/^\/+/, ''));
    mkdirSync(dirname(target), { recursive: true });
    cpSync(source, target, { recursive: true });
  }
  return rootfs;
}
