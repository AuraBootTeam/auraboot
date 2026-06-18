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
        // Ratchet — consolidated 2026-06-19 (wave 4). The floors lagged actual by ~5pt:
        // a fresh full run measured lines 30.17 / stmts 29.62 / funcs 27.39 / branches
        // 24.55 (423 spec files / 4342 tests) but the floors were still at the round-2
        // values. Raised to lock in the achieved coverage (~2-3pt flaky margin) and
        // extended ActionRegistry handler coverage (navigate/new/search/reset/setState +
        // error branches). NOTE: vitest covers logic (services / hooks / engines / utils);
        // React presentation components are covered by Playwright E2E, so the vitest line
        // ceiling is ~30% — reaching 80% line needs the coverage:e2e harness merged with
        // vitest (GA stack) or a redefined target, NOT more component unit tests (tracker §7,
        // owner task #14). The logic layer is now near-exhausted (~5 .ts modules remain).
        lines: 28,
        statements: 27,
        functions: 25,
        branches: 22,
      },
    },
    // 禁用 watch 模式的交互提示
    watch: false, // 禁用 watch 模式
    // 或者如果需要 watch 模式但不要提示，可以使用：
    // watchExclude: ['**/node_modules/**', '**/build/**'],
    // silent: true, // 减少输出
  },
});
