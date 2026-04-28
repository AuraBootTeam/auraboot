import { reactRouter } from '@react-router/dev/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig, type Plugin } from 'vite';
import tsconfigPaths from 'vite-tsconfig-paths';
import federation from '@originjs/vite-plugin-federation';
import istanbul from 'vite-plugin-istanbul';

const e2eCoverageEnabled = process.env.E2E_COVERAGE === '1';
const bffProxyTarget = `http://127.0.0.1:${process.env.BFF_PORT || '3500'}`;

// @originjs/vite-plugin-federation 1.4.x does not support SSR — its virtual
// imports (`__federation_fn_satisfy`, etc.) are emitted unconditionally and
// fail to resolve during the SSR Rollup pass. The host runtime
// (FederationManager.ts) is browser-only (uses `document.createElement`), so
// the SSR bundle never invokes federation anyway. Override the plugin's
// `apply` so it only activates during dev or the client production build,
// and stays out of the SSR Rollup pass entirely.
const federationPlugin = federation({
  name: 'aura-host',
  remotes: {},
  shared: {
    react: { requiredVersion: '^19.0.0' },
    'react-dom': { requiredVersion: '^19.0.0' },
    'react-router': { requiredVersion: '7.5.0' },
    zustand: { requiredVersion: '^5.0.8' },
    '@reduxjs/toolkit': {},
    'lucide-react': {},
  },
}) as Plugin;

const clientOnlyFederationPlugin: Plugin = {
  ...federationPlugin,
  apply(_config, env) {
    // Always run during dev (`serve`). For production (`build`), skip the
    // SSR pass — that is when federation's virtual imports fail to resolve.
    if (env.command === 'serve') return true;
    return !env.isSsrBuild;
  },
};

export default defineConfig({
  plugins: [
    e2eCoverageEnabled &&
      istanbul({
        include: 'app/**/*',
        exclude: ['node_modules', 'tests', 'test-results'],
        extension: ['.js', '.ts', '.tsx'],
        requireEnv: false,
      }),
    tailwindcss(),
    reactRouter(),
    tsconfigPaths(),
    clientOnlyFederationPlugin,
  ].filter(Boolean),
  server: {
    host: '0.0.0.0',
    port: Number(process.env.VITE_PORT || 5173),
    strictPort: true,
    // Allow browser devtools/extensions to probe source modules in local dev
    // without tripping CORS errors on HEAD/GET requests.
    cors: true,
    fs: {
      allow: ['..'],
    },
    watch: {
      // NOTE: do NOT use bare `**/plugins/**` — that pattern also matches
      // `app/plugins/**` (the entire frontend plugin tree) and silently
      // disables HMR for every frontend plugin file (manifesting as "edits
      // never take effect until dev server restart"). Restrict the backend
      // plugins ignore to absolute / repo-relative paths.
      ignored: [
        '**/platform/**',
        '**/ios/**',
        '**/android/**',
        '**/docs/**',
        '**/tests/**',
        'tests/**',
        './tests/**',
        '**/test-results/**',
        'test-results/**',
        './test-results/**',
        '/plugins/**',
        '../plugins/**',
        '../../plugins/**',
        '**/scripts/**',
        '**/.gradle/**',
        '**/.git/**',
      ],
    },
    proxy: {
      '/api/notifications/stream': {
        target: bffProxyTarget,
        changeOrigin: true,
        secure: false,
        configure: (proxy, _options) => {
          console.log(`🔔 Proxying SSE /api/notifications/stream to BFF server`);
          proxy.on('proxyReq', (proxyReq, _req, _res) => {
            proxyReq.setHeader('Cache-Control', 'no-cache');
            proxyReq.setHeader('Connection', 'keep-alive');
          });
          proxy.on('proxyRes', (proxyRes, _req, _res) => {
            proxyRes.headers['cache-control'] = 'no-cache';
            proxyRes.headers['x-accel-buffering'] = 'no';
          });
        },
      },
      '^/api/': {
        target: bffProxyTarget,
        changeOrigin: true,
        secure: false,
        configure: (_proxy, _options) => {
          console.log(
            `🔗 Proxying /api/* requests to BFF server at ${bffProxyTarget}`,
          );
        },
      },
    },
  },
  build: {
    modulePreload: false,
    target: 'esnext',
    minify: 'esbuild',
    cssCodeSplit: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          // Isolate heavy vendor libraries into separate chunks
          // so they are only loaded when the consuming route is visited.
          if (id.includes('node_modules/xlsx')) return 'vendor-xlsx';
          if (id.includes('node_modules/jspdf')) return 'vendor-jspdf';
          if (id.includes('node_modules/html2canvas')) return 'vendor-html2canvas';
          if (id.includes('node_modules/recharts') || id.includes('node_modules/d3-')) return 'vendor-charts';
          if (id.includes('node_modules/@dnd-kit')) return 'vendor-dnd-kit';
          if (id.includes('node_modules/reactflow') || id.includes('node_modules/@reactflow')) return 'vendor-reactflow';
          if (id.includes('node_modules/@bpmn-io') || id.includes('node_modules/bpmn-js')) return 'vendor-bpmn';
          if (id.includes('node_modules/@tanstack/react-virtual')) return 'vendor-virtual';
        },
      },
    },
  },
  // SSR: force-bundle CJS packages that don't work with ESM import
  ssr: {
    noExternal: ['gray-matter', '@mdx-js/mdx', 'remark-gfm', 'rehype-highlight', 'reading-time'],
  },
  logLevel: 'info',
});
