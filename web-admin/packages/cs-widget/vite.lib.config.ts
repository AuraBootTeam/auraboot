import { defineConfig } from 'vite';

/**
 * Builds the embeddable customer-service widget.
 *   pnpm --filter @auraboot/cs-widget build  →  dist/aura-cs.global.js
 *
 * IIFE exposing `window.AuraCS`, all dependencies bundled: this runs on a customer's own
 * website behind a single <script> tag, where no module loader can be assumed. Mirrors the
 * @auraboot/track build, which is the proven shape for a bundle we hand to third parties.
 */
export default defineConfig({
  build: {
    lib: {
      entry: 'src/global.ts',
      name: 'AuraCS',
      formats: ['iife'],
      fileName: () => 'aura-cs.global.js',
    },
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: true,
    minify: true,
  },
});
