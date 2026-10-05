import { existsSync, readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export function verifyRegistrationPayload(releaseRoot, imageRoot, required) {
  if (typeof required !== 'boolean') throw new Error('Registration requirement must be boolean');
  const releaseFile = resolve(releaseRoot, 'release-registration.json');
  const imageFile = resolve(imageRoot, 'release-registration.json');
  if (!required && !existsSync(releaseFile) && !existsSync(imageFile)) return { registration: 'not-declared' };
  for (const file of [releaseFile, imageFile]) {
    if (!existsSync(file) || !statSync(file).isFile()) {
      throw new Error(`Release registration payload missing: ${file}`);
    }
  }
  if (!readFileSync(releaseFile).equals(readFileSync(imageFile))) {
    throw new Error('Release image registration differs from the locked release payload');
  }
  return { registration: 'identical' };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [releaseRoot, imageRoot, required] = process.argv.slice(2);
    if (!releaseRoot || !imageRoot || !['0', '1'].includes(required) || process.argv.length !== 5) {
      throw new Error('Usage: verify-release-registration-payload.mjs <release-root> <image-root> <required:0|1>');
    }
    process.stdout.write(`${JSON.stringify(verifyRegistrationPayload(releaseRoot, imageRoot, required === '1'))}\n`);
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
