import { reactRouter } from '@react-router/dev/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';
import tsconfigPaths from 'vite-tsconfig-paths';
import federation from '@originjs/vite-plugin-federation';
import istanbul from 'vite-plugin-istanbul';

const e2eCoverageEnabled = process.env.E2E_COVERAGE === '1';

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
    // Module Federation for plugin hot-loading
    federation({
      name: 'aura-host',
      // Remote plugins will be configured dynamically
      remotes: {},
      // Shared dependencies for plugins
      shared: {
        react: {
          requiredVersion: '^19.0.0',
        },
        'react-dom': {
          requiredVersion: '^19.0.0',
        },
        'react-router': {
          requiredVersion: '7.5.0',
        },
        zustand: {
          requiredVersion: '^5.0.8',
        },
        '@reduxjs/toolkit': {},
        'lucide-react': {},
      },
    }),
  ].filter(Boolean),
  server: {
    host: '0.0.0.0',
    port: Number(process.env.VITE_PORT || 5173),
    strictPort: true,
    fs: {
      allow: ['..'],
    },
    watch: {
      ignored: [
        '**/platform/**',
        '**/ios/**',
        '**/android/**',
        '**/docs/**',
        '**/plugins/**',
        '**/scripts/**',
        '**/.gradle/**',
        '**/.git/**',
      ],
    },
    proxy: {
      '/api/notifications/stream': {
        target: `http://localhost:${process.env.BFF_PORT || '3500'}`,
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          console.log(`🔔 Proxying SSE /api/notifications/stream to BFF server`);
          proxy.on('proxyReq', (proxyReq, req, res) => {
            proxyReq.setHeader('Cache-Control', 'no-cache');
            proxyReq.setHeader('Connection', 'keep-alive');
          });
          proxy.on('proxyRes', (proxyRes, req, res) => {
            proxyRes.headers['cache-control'] = 'no-cache';
            proxyRes.headers['x-accel-buffering'] = 'no';
          });
        },
      },
      '^/api/': {
        target: `http://localhost:${process.env.BFF_PORT || '3500'}`,
        changeOrigin: true,
        secure: false,
        configure: (proxy, options) => {
          console.log(
            `🔗 Proxying /api/* requests to BFF server at http://localhost:${process.env.BFF_PORT || '3500'}`,
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
