import { defineConfig } from 'vitest/config';
import tsconfigPaths from 'vite-tsconfig-paths';

export default defineConfig({
  plugins: [tsconfigPaths()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    include: [
      '**/__tests__/**/*.test.ts',
      '**/__tests__/**/*.test.tsx',
      '**/*.test.ts',
      '**/*.test.tsx',
    ],
    exclude: ['node_modules', 'build', 'tests'],
    // 性能优化配置
    testTimeout: 30000, // 单个测试最大超时时间：30秒
    hookTimeout: 10000, // 钩子函数超时时间：10秒
    teardownTimeout: 5000, // 清理超时时间：5秒
    pool: 'threads',
    isolate: true,
    // 性能监控
    logHeapUsage: true,
    // 快速失败
    bail: 0, // 不快速失败，运行所有测试
    // 重试配置
    retry: 0, // 不重试失败的测试，避免延长时间
    // 报告配置
    reporters: ['verbose', 'json'],
    outputFile: {
      json: './test-results/results.json',
    },
    // ── Coverage (v8) ──
    // Baseline 2026-06-10: lines 19.08% / statements 18.79% / functions 16.43% /
    // branches 16.44% across app + packages (285 spec files, 2099 tests).
    // Thresholds act as a no-regression ratchet — raise them in lockstep as the
    // coverage initiative adds tests toward the 80% target. UI presentation code
    // stays covered by Playwright E2E; vitest targets hooks / utils / renderers /
    // registries / pure decision logic.
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'json-summary', 'html'],
      reportsDirectory: './coverage',
      all: true,
      include: ['app/**/*.{ts,tsx}', 'packages/**/*.{ts,tsx}'],
      exclude: [
        'node_modules',
        'build',
        'tests',
        '**/__tests__/**',
        '**/*.test.{ts,tsx}',
        '**/*.d.ts',
        '**/*.config.{ts,js,mts}',
        '**/types/**',
      ],
      thresholds: {
        // Ratchet — raised 2026-06-11 after coverage PRs #529/#531/#532/#533/#534
        // (services + hooks + server/stores). Measured: lines 22.48 / stmts 22.09 /
        // funcs 19.61 / branches 18.54. Floors sit just under measured for flaky margin.
        lines: 22,
        statements: 21,
        functions: 19,
        branches: 18,
      },
    },
    // 禁用 watch 模式的交互提示
    watch: false, // 禁用 watch 模式
    // 或者如果需要 watch 模式但不要提示，可以使用：
    // watchExclude: ['**/node_modules/**', '**/build/**'],
    // silent: true, // 减少输出
  },
});
