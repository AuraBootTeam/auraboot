import { execFileSync, spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';

const LINUX_JRE_BASE = 'eclipse-temurin:21-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f';

function commandExists(command) {
  return spawnSync('which', [command], { stdio: 'ignore' }).status === 0;
}

function createRunnableLayout({ output, rootfs, labels, entrypoint }) {
  if (process.platform !== 'linux' || process.arch !== 'x64') {
    throw new Error('release OCI images must be built on the self-hosted Linux CI Docker builder (linux/amd64)');
  }
  if (process.env.AURA_OCI_BUILDER && process.env.AURA_OCI_BUILDER !== 'docker') {
    throw new Error('AURA_OCI_BUILDER must be docker; Apple Container and local fallbacks are prohibited');
  }
  if (!commandExists('docker')) throw new Error('Docker buildx is required on the self-hosted Linux CI builder');

  mkdirSync(dirname(output), { recursive: true });
  const context = mkdtempSync(resolve(dirname(output), '.auraboot-image-'));
  const archive = resolve(context, 'image.tar');
  const contextRootfs = resolve(context, 'rootfs');
  const tag = `auraboot-ci-${process.pid}:build`;
  try {
    cpSync(rootfs, contextRootfs, { recursive: true });
    writeFileSync(resolve(context, 'Dockerfile'), [
      `FROM ${LINUX_JRE_BASE}`,
      // CVE-2026-109518 is still present in the pinned upstream JRE image.
      // HTTPS plus zero retries protects the old APT during its own signed update.
      "RUN cp /etc/apt/sources.list.d/ubuntu.sources /tmp/auraboot-ubuntu.sources " +
        "&& sed -i -e 's|http://|https://|g' -e 's/^Components:.*/Components: main/' " +
        "/etc/apt/sources.list.d/ubuntu.sources " +
        "&& apt-get -o Acquire::Retries=0 -o Acquire::https::Timeout=30 update --error-on=any " +
        "&& apt-get -o Acquire::Retries=0 -o Acquire::https::Timeout=30 install --no-install-recommends -y " +
        "apt=2.8.4ubuntu0.1 libapt-pkg6.0t64=2.8.4ubuntu0.1 libpcre2-8-0=10.42-4ubuntu2.2 " +
        "libc6=2.39-0ubuntu8.12 libc-bin=2.39-0ubuntu8.12 locales=2.39-0ubuntu8.12 perl-base=5.38.2-3.2ubuntu0.7 " +
        "&& dpkg-query -W apt libapt-pkg6.0t64 libpcre2-8-0 libc6 libc-bin locales perl-base " +
        "&& sed 's|http://|https://|g' /tmp/auraboot-ubuntu.sources > /etc/apt/sources.list.d/ubuntu.sources " +
        "&& rm /tmp/auraboot-ubuntu.sources && rm -rf /var/lib/apt/lists/*",
      'COPY rootfs/ /',
      `ENTRYPOINT ${JSON.stringify(entrypoint)}`,
      '',
    ].join('\n'));

    const labelArgs = Object.entries(labels).flatMap(([key, value]) => ['--label', `${key}=${value}`]);
    execFileSync('docker', [
      'buildx', 'build', '--progress', 'plain',
      '--platform', 'linux/amd64',
      '--tag', tag,
      '--output', `type=oci,dest=${archive}`,
      ...labelArgs,
      context,
    ], { stdio: 'inherit' });

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
