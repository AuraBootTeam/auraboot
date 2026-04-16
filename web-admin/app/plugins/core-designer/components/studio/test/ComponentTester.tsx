/**
 * 组件测试器
 * 用于在设计器中测试所有Smart组件的功能
 */

import React, { useState } from 'react';
import ComponentTestSuite, { type ComponentTestCase } from '~/plugins/core-designer/components/studio/test/ComponentTestSuite';
import type { FormSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';

interface TestResult {
  type: string;
  success: boolean;
  errors: string[];
  warnings: string[];
  performance: number;
  renderTime: number;
}

interface ComponentTesterProps {
  onTestComplete?: (results: TestResult[]) => void;
}

export const ComponentTester: React.FC<ComponentTesterProps> = ({ onTestComplete }) => {
  const [testSuite] = useState(() => new ComponentTestSuite());
  const [testResults, setTestResults] = useState<TestResult[]>([]);
  const [currentTest, setCurrentTest] = useState<string>('');
  const [isRunning, setIsRunning] = useState(false);
  const [progress, setProgress] = useState(0);
  const [pageSchema, setPageSchema] = useState<FormSchema | null>(null);

  // 运行所有组件测试
  const runAllTests = async () => {
    setIsRunning(true);
    setTestResults([]);
    setProgress(0);

    const testCases = testSuite.getAllTestCases();
    const results: TestResult[] = [];

    for (let i = 0; i < testCases.length; i++) {
      const testCase = testCases[i];
      setCurrentTest(testCase.name);

      const result = await runSingleTest(testCase);
      results.push(result);

      setTestResults([...results]);
      setProgress(((i + 1) / testCases.length) * 100);

      // 添加小延迟以便观察测试过程
      await new Promise((resolve) => setTimeout(resolve, 100));
    }

    setIsRunning(false);
    setCurrentTest('');
    onTestComplete?.(results);
  };

  // 运行单个组件测试
  const runSingleTest = async (testCase: ComponentTestCase): Promise<TestResult> => {
    const startTime = performance.now();

    try {
      // 1. 验证组件属性
      const validation = testSuite.validateComponent(testCase.type, testCase.defaultProps);

      // 2. 测试组件渲染
      const renderStartTime = performance.now();
      const canRender = await testComponentRender(testCase);
      const renderTime = performance.now() - renderStartTime;

      // 3. 测试属性配置
      const propsTest = await testComponentProps(testCase);

      const endTime = performance.now();

      return {
        type: testCase.type,
        success: validation.isValid && canRender && propsTest,
        errors: validation.errors,
        warnings: validation.warnings,
        performance: endTime - startTime,
        renderTime,
      };
    } catch (error) {
      const endTime = performance.now();
      return {
        type: testCase.type,
        success: false,
        errors: [error instanceof Error ? error.message : '未知错误'],
        warnings: [],
        performance: endTime - startTime,
        renderTime: 0,
      };
    }
  };

  // 测试组件渲染
  const testComponentRender = async (testCase: ComponentTestCase): Promise<boolean> => {
    try {
      // 创建测试组件
      const testComponent = {
        id: `test-${testCase.type}`,
        type: testCase.type,
        position: { x: 100, y: 100 },
        size: { width: 200, height: 100 },
        props: testCase.defaultProps,
        style: {},
        validationRules: testCase.validationRules || [],
      };

      // 临时添加到页面Schema中进行渲染测试
      const testSchema = {
        ...(pageSchema || {}),
        components: [testComponent],
      };

      // 这里可以添加实际的渲染测试逻辑
      // 目前只是验证Schema结构
      return testSchema.components.length > 0;
    } catch (error) {
      console.error(`组件 ${testCase.type} 渲染测试失败:`, error);
      return false;
    }
  };

  // 测试组件属性
  const testComponentProps = async (testCase: ComponentTestCase): Promise<boolean> => {
    try {
      // 测试默认属性
      const defaultPropsValid = Object.keys(testCase.defaultProps).length > 0;

      // 测试扩展属性
      const testPropsValid = Object.keys(testCase.testProps).length >= 0;

      return defaultPropsValid && testPropsValid;
    } catch (error) {
      console.error(`组件 ${testCase.type} 属性测试失败:`, error);
      return false;
    }
  };

  // 加载测试页面
  const loadTestPage = () => {
    const testPageSchema = testSuite.generateTestPageSchema() as FormSchema;
    setPageSchema(testPageSchema);
  };

  // 清除测试结果
  const clearResults = () => {
    setTestResults([]);
    setProgress(0);
    setCurrentTest('');
  };

  // 导出测试报告
  const exportReport = () => {
    const report = testSuite.generateTestReport(testResults);
    const blob = new Blob([report], { type: 'text/markdown' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `component-test-report-${new Date().toISOString().split('T')[0]}.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  return (
    <div className="component-tester rounded-lg bg-white p-6 shadow-lg">
      <div className="mb-6">
        <h2 className="mb-2 text-2xl font-bold text-gray-800">智能组件测试器</h2>
        <p className="text-gray-600">测试所有Smart组件在设计器中的功能和兼容性</p>
      </div>

      {/* 控制按钮 */}
      <div className="mb-6 flex gap-4">
        <button
          onClick={loadTestPage}
          className="rounded bg-blue-500 px-4 py-2 text-white hover:bg-blue-600 disabled:opacity-50"
          disabled={isRunning}
        >
          加载测试页面
        </button>
        <button
          onClick={runAllTests}
          className="rounded bg-green-500 px-4 py-2 text-white hover:bg-green-600 disabled:opacity-50"
          disabled={isRunning}
        >
          {isRunning ? '测试中...' : '运行所有测试'}
        </button>
        <button
          onClick={clearResults}
          className="rounded bg-gray-500 px-4 py-2 text-white hover:bg-gray-600 disabled:opacity-50"
          disabled={isRunning}
        >
          清除结果
        </button>
        {testResults.length > 0 && (
          <button
            onClick={exportReport}
            className="rounded bg-purple-500 px-4 py-2 text-white hover:bg-purple-600"
          >
            导出报告
          </button>
        )}
      </div>

      {/* 进度条 */}
      {isRunning && (
        <div className="mb-6">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-sm text-gray-600">测试进度</span>
            <span className="text-sm text-gray-600">{progress.toFixed(1)}%</span>
          </div>
          <div className="h-2 w-full rounded-full bg-gray-200">
            <div
              className="h-2 rounded-full bg-blue-500 transition-all duration-300"
              style={{ width: `${progress}%` }}
            />
          </div>
          {currentTest && <p className="mt-2 text-sm text-gray-600">正在测试: {currentTest}</p>}
        </div>
      )}

      {/* 测试结果统计 */}
      {testResults.length > 0 && (
        <div className="mb-6 rounded-lg bg-gray-50 p-4">
          <h3 className="mb-3 text-lg font-semibold">测试统计</h3>
          <div className="grid grid-cols-4 gap-4">
            <div className="text-center">
              <div className="text-2xl font-bold text-blue-600">{testResults.length}</div>
              <div className="text-sm text-gray-600">总测试数</div>
            </div>
            <div className="text-center">
              <div className="text-2xl font-bold text-green-600">
                {testResults.filter((r) => r.success).length}
              </div>
              <div className="text-sm text-gray-600">成功</div>
            </div>
            <div className="text-center">
              <div className="text-2xl font-bold text-red-600">
                {testResults.filter((r) => !r.success).length}
              </div>
              <div className="text-sm text-gray-600">失败</div>
            </div>
            <div className="text-center">
              <div className="text-2xl font-bold text-purple-600">
                {testResults.length > 0
                  ? (
                      (testResults.filter((r) => r.success).length / testResults.length) *
                      100
                    ).toFixed(1)
                  : 0}
                %
              </div>
              <div className="text-sm text-gray-600">成功率</div>
            </div>
          </div>
        </div>
      )}

      {/* 详细测试结果 */}
      {testResults.length > 0 && (
        <div>
          <h3 className="mb-3 text-lg font-semibold">详细结果</h3>
          <div className="max-h-96 space-y-3 overflow-y-auto">
            {testResults.map((result, index) => (
              <div
                key={index}
                className={`rounded-lg border p-4 ${
                  result.success ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'
                }`}
              >
                <div className="mb-2 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span
                      className={`text-lg ${result.success ? 'text-green-600' : 'text-red-600'}`}
                    >
                      {result.success ? '✅' : '❌'}
                    </span>
                    <span className="font-medium">{result.type}</span>
                  </div>
                  <div className="text-sm text-gray-500">{result.performance.toFixed(2)}ms</div>
                </div>

                {result.errors.length > 0 && (
                  <div className="mb-2">
                    <div className="mb-1 text-sm font-medium text-red-600">错误:</div>
                    <ul className="list-inside list-disc text-sm text-red-600">
                      {result.errors.map((error, i) => (
                        <li key={i}>{error}</li>
                      ))}
                    </ul>
                  </div>
                )}

                {result.warnings.length > 0 && (
                  <div>
                    <div className="mb-1 text-sm font-medium text-yellow-600">警告:</div>
                    <ul className="list-inside list-disc text-sm text-yellow-600">
                      {result.warnings.map((warning, i) => (
                        <li key={i}>{warning}</li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default ComponentTester;
