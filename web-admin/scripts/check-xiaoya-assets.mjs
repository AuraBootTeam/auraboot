import { createHash } from 'node:crypto';
import { closeSync, constants, existsSync, fstatSync, openSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '../public/static/xiaoya');
const manifest = JSON.parse(readFileSync(join(root, 'asset-manifest.json'), 'utf8'));
const runtimeFiles = manifest.assets
  .flatMap((asset) => asset.files || [])
  .filter((file) => file.path.startsWith('runtime/static/xiaoya/'))
  .filter((file) => /(?:\.svg|\.png|\.json|\/engine\.js)$/.test(file.path));

const failures = [];
for (const file of runtimeFiles) {
  const relative = file.path.replace('runtime/static/xiaoya/', '');
  const target = join(root, relative);
  if (!existsSync(target)) {
    failures.push(`missing ${relative}`);
    continue;
  }
  const descriptor = openSync(target, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  let contents;
  try {
    if (!fstatSync(descriptor).isFile()) throw new Error('Asset must be a regular file');
    contents = readFileSync(descriptor);
  } finally { closeSync(descriptor); }
  const bytes = contents.length;
  const sha256 = createHash('sha256').update(contents).digest('hex');
  if (bytes !== file.bytes) failures.push(`size ${relative}: ${bytes} != ${file.bytes}`);
  if (sha256 !== file.sha256) failures.push(`sha256 ${relative}: ${sha256} != ${file.sha256}`);
  if (relative.endsWith('.svg')) {
    const svg = contents.toString('utf8');
    if (bytes > 80_000) failures.push(`oversize SVG ${relative}: ${bytes}`);
    if (!/viewBox="0 0 512 512"/.test(svg)) failures.push(`canvas ${relative}`);
    if (/<(?:image|script|text|foreignObject)\b/i.test(svg))
      failures.push(`forbidden embedded content ${relative}`);
  }
  if (relative.endsWith('.png')) {
    const png = contents;
    const expectedDimension = relative.startsWith('class-tree/') ? 2048 : 1024;
    if (png.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') failures.push(`signature ${relative}`);
    if (png.toString('ascii', 12, 16) !== 'IHDR') failures.push(`header ${relative}`);
    if (png.length < 26 || png.readUInt32BE(16) !== expectedDimension || png.readUInt32BE(20) !== expectedDimension)
      failures.push(`dimensions ${relative}`);
    if (png.length < 26 || png[25] !== 6) failures.push(`RGBA ${relative}`);
  }
}

if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log(`XIAOYA ASSET CONTRACT PASS: ${runtimeFiles.length} checksummed runtime files`);
