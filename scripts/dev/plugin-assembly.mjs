import { pathToFileURL } from 'node:url';

/** Profile omission is an installation choice, never an import permission bypass. */
export function pluginAssemblyDecision(manifest, discovery, fromProfile) {
  const modules = manifest.applicationModules ?? [];
  if (!Array.isArray(modules) || modules.some(module => module !== 'agent')) {
    throw new Error('Unsupported plugin application module requirement');
  }
  if (!modules.length) return 'import';
  const state = discovery?.schemaVersion === 1 ? discovery?.agent?.state : 'unknown';
  if (state === 'installed') return 'import';
  if (state === 'not_installed' && fromProfile) return 'omit';
  throw new Error(state === 'not_installed' ? 'Required agent module is not installed' : 'Cannot verify required agent module');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    console.log(pluginAssemblyDecision(JSON.parse(process.argv[2]), JSON.parse(process.argv[3]), process.argv[4] === '1'));
  } catch (error) { console.error(error.message); process.exitCode = 2; }
}
