import { statfsSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

// Reserve space for the staged boot jar, database import, logs, and browser
// evidence before creating a verification allocation. This is a floor, not
// a guarantee against concurrent disk growth during the gate.
export const MIN_FREE_BYTES = 1024n * 1024n * 1024n;

export function assertDiskSpace(filesystem) {
  const free = filesystem.bavail * filesystem.bsize;
  if (free < MIN_FREE_BYTES) {
    throw new Error(`environment-invalid: verification volume has ${free / (1024n * 1024n)} MiB available; requires at least 1024 MiB before allocation`);
  }
  return free;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const free = assertDiskSpace(statfsSync(process.argv[2], { bigint: true }));
    console.log(`Verification disk preflight: ${free / (1024n * 1024n)} MiB available`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 2;
  }
}
