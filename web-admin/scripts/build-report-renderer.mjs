import { createRequire } from 'node:module';
import { chmod, copyFile, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(resolve(root, 'package.json'));
const playwrightRequire = createRequire(require.resolve('@playwright/test/package.json'));
const outDir = resolve(root, 'build/report-renderer');

const runtimeDir = resolve(root, 'report-renderer-runtime');
const runtimePackage = JSON.parse(await readFile(resolve(runtimeDir, 'package.json'), 'utf8'));
const runtimeLock = JSON.parse(await readFile(resolve(runtimeDir, 'package-lock.json'), 'utf8'));
const installedVersions = {
  echarts: require('echarts/package.json').version,
  jsbarcode: require('jsbarcode/package.json').version,
  playwright: playwrightRequire('playwright/package.json').version,
};
for (const [name, version] of Object.entries(installedVersions)) {
  if (runtimePackage.dependencies?.[name] !== version ||
      runtimeLock.packages?.['']?.dependencies?.[name] !== version ||
      runtimeLock.packages?.[`node_modules/${name}`]?.version !== version) {
    throw new Error(`Renderer dependency ${name} differs from the installed Web version ${version}; update its runtime manifest and lockfile together.`);
  }
}

// Compile the shared print implementation, keeping only explicit runtime packages external.
await build({
  configFile: false,
  root,
  build: {
    ssr: resolve(root, 'app/framework/smart/report-export/cli.ts'),
    outDir,
    emptyOutDir: true,
    sourcemap: false,
    minify: false,
    rollupOptions: { output: { entryFileNames: 'cli.js' } },
  },
});
await copyFile(resolve(runtimeDir, 'package.json'), resolve(outDir, 'package.json'));
await copyFile(resolve(runtimeDir, 'package-lock.json'), resolve(outDir, 'package-lock.json'));
await copyFile(resolve(runtimeDir, 'render-report'), resolve(outDir, 'render-report'));
await chmod(resolve(outDir, 'render-report'), 0o755);
console.log(`Report renderer runtime written to ${outDir}`);
