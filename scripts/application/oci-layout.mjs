import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  closeSync, cpSync, mkdirSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync,
} from 'node:fs';
import { dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';

const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');

function writeBlob(layout, bytes) {
  const hex = digest(bytes);
  const path = resolve(layout, 'blobs/sha256', hex);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, bytes);
  return { digest: `sha256:${hex}`, size: bytes.length };
}

/** Create a standards-compliant single-platform OCI image layout from a prepared rootfs. */
export function createOciImageLayout({ output, rootfs, labels = {}, entrypoint = [] }) {
  mkdirSync(output, { recursive: true });
  writeFileSync(resolve(output, 'oci-layout'), `${JSON.stringify({ imageLayoutVersion: '1.0.0' })}\n`);
  const temporary = mkdtempSync(resolve(tmpdir(), 'auraboot-oci-'));
  try {
    const tarPath = resolve(temporary, 'layer.tar');
    const gzipPath = `${tarPath}.gz`;
    execFileSync('tar', ['-cf', tarPath, '-C', rootfs, '.']);
    const gzipFd = openSync(gzipPath, 'w');
    try {
      execFileSync('gzip', ['-n', '-c', tarPath], { stdio: ['ignore', gzipFd, 'inherit'] });
    } finally {
      closeSync(gzipFd);
    }
    const tarBytes = readFileSync(tarPath);
    const layerBytes = readFileSync(gzipPath);
    const layer = writeBlob(output, layerBytes);
    const configBytes = Buffer.from(JSON.stringify({
      architecture: process.arch === 'arm64' ? 'arm64' : 'amd64',
      os: 'linux',
      config: { Entrypoint: entrypoint, Labels: labels },
      rootfs: { type: 'layers', diff_ids: [`sha256:${digest(tarBytes)}`] },
      history: [{ created_by: 'AuraBoot deterministic application assembler' }],
    }));
    const config = writeBlob(output, configBytes);
    const manifestBytes = Buffer.from(JSON.stringify({
      schemaVersion: 2,
      mediaType: 'application/vnd.oci.image.manifest.v1+json',
      config: { mediaType: 'application/vnd.oci.image.config.v1+json', ...config },
      layers: [{ mediaType: 'application/vnd.oci.image.layer.v1.tar+gzip', ...layer }],
      annotations: labels,
    }));
    const manifest = writeBlob(output, manifestBytes);
    writeFileSync(resolve(output, 'index.json'), `${JSON.stringify({
      schemaVersion: 2,
      mediaType: 'application/vnd.oci.image.index.v1+json',
      manifests: [{
        mediaType: 'application/vnd.oci.image.manifest.v1+json',
        ...manifest,
        platform: { architecture: process.arch === 'arm64' ? 'arm64' : 'amd64', os: 'linux' },
        annotations: labels,
      }],
    }, null, 2)}\n`);
    return manifest.digest;
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
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
