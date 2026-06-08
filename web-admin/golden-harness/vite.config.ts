import { defineConfig } from 'vite'
import path from 'node:path'
export default defineConfig({
  root: __dirname,
  resolve: { alias: { '~': path.resolve(__dirname, '../app') } },
  esbuild: { jsx: 'automatic', jsxImportSource: 'react' },
  server: { port: 5199, host: '127.0.0.1' },
})
