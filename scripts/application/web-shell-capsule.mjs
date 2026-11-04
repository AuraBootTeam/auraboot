// Restore the separately packaged pnpm overrides before frozen installation.
// npm pack strips pnpm.overrides; the immutable capsule retains their exact map.
import {readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
export function hydrateCapsuleOverrides(root) {
  const manifestPath=resolve(root,'package.json');
  const overrides=JSON.parse(readFileSync(resolve(root,'pnpm-overrides.json'),'utf8'));
  if(!overrides || typeof overrides!=='object' || Array.isArray(overrides)
    || Object.values(overrides).some(value=>typeof value!=='string')) throw Error('Invalid capsule override map');
  const manifest=JSON.parse(readFileSync(manifestPath,'utf8'));
  manifest.pnpm={...(manifest.pnpm??{}),overrides};
  writeFileSync(manifestPath,`${JSON.stringify(manifest,null,2)}\n`);
}
if(process.argv[1]&&pathToFileURL(resolve(process.argv[1])).href===import.meta.url) {
  if(process.argv.length!==3) throw Error('Usage: web-shell-capsule.mjs <extracted-capsule-root>');
  hydrateCapsuleOverrides(process.argv[2]);
}
