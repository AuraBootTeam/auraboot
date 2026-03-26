import { defineConfig } from 'vitest/config';
import tsconfigPaths from 'vite-tsconfig-paths';

export default defineConfig({
  plugins: [tsconfigPaths()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    include: ['**/__tests__/**/*.test.ts', '**/__tests__/**/*.test.tsx', '**/*.test.ts', '**/*.test.tsx'],
    exclude: ['node_modules', 'build', 'tests'],
    // 性能优化配置
    testTimeout: 30000, // 单个测试最大超时时间：30秒
    hookTimeout: 10000,  // 钩子函数超时时间：10秒
    teardownTimeout: 5000, // 清理超时时间：5秒
    // 并发配置 - 优化内存使用
    pool: 'threads',
    poolOptions: {
      threads: {
        singleThread: true, // 使用单线程避免内存问题
        isolate: false, // 减少隔离开销
        minThreads: 1,
        maxThreads: 1, // 限制为单线程
      }
    },
    // 性能监控
    logHeapUsage: true,
    // 快速失败
    bail: 0, // 不快速失败，运行所有测试
    // 重试配置
    retry: 0, // 不重试失败的测试，避免延长时间
    // 报告配置
    reporters: ['verbose', 'json'],
    outputFile: {
      json: './test-results/results.json'
    },
    // 禁用 watch 模式的交互提示
    watch: false, // 禁用 watch 模式
    // 或者如果需要 watch 模式但不要提示，可以使用：
    // watchExclude: ['**/node_modules/**', '**/build/**'],
    // silent: true, // 减少输出
  },
});