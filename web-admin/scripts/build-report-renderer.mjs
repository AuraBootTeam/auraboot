import { createRequire } from 'node:module';
import { writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(resolve(root, 'package.json'));
const playwrightRequire = createRequire(require.resolve('@playwright/test/package.json'));
const outDir = resolve(root, 'build/report-renderer');

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
await writeFile(resolve(outDir, 'package.json'), JSON.stringify({
  name: '@auraboot/report-renderer-runtime',
  version: '0.0.0',
  private: true,
  type: 'module',
  scripts: { start: 'node cli.js' },
  dependencies: {
    echarts: require('echarts/package.json').version,
    jsbarcode: require('jsbarcode/package.json').version,
    playwright: playwrightRequire('playwright/package.json').version,
  },
}, null, 2) + '\n');
console.log(`Report renderer runtime written to ${outDir}`);
