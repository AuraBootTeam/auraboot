import { defineConfig } from 'vite';

/**
 * Builds the embeddable public-mode SDK bundle for published low-code apps.
 *   pnpm --filter @auraboot/track build  →  dist/aura-track.global.js
 *
 * IIFE format exposes `window.AuraTrack` (with `.init`). All deps are bundled so
 * the artifact drops into any page via a <script> tag with no module loader.
 * Run from the package dir (npm run build sets cwd here), so `entry` is relative.
 */
export default defineConfig({
  build: {
    lib: {
      entry: 'src/global.ts',
      name: 'AuraTrack',
      formats: ['iife'],
      fileName: () => 'aura-track.global.js',
    },
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: true,
    minify: true,
  },
});
