/**
 * 组件测试套件
 * 用于测试所有Smart组件在设计器中的功能
 */

import type { PageSchema, Block } from '~/plugins/core-designer/components/studio/domain/schema/types';

export interface ComponentTestCase {
  type: string;
  name: string;
  category: 'form' | 'display' | 'interaction' | 'layout' | 'datetime';
  defaultProps: Record<string, any>;
  testProps: Record<string, any>;
  validationRules?: any[];
  expectedBehavior: string[];
}

export class ComponentTestSuite {
  private testCases: ComponentTestCase[] = [];

  constructor() {
    this.initializeTestCases();
  }

  private initializeTestCases() {
    // 表单组件测试用例
    this.testCases.push(
      {
        type: 'SmartInput',
        name: '智能输入框',
        category: 'form',
        defaultProps: {
          label: '用户名',
          placeholder: '请输入用户名',
          required: true,
          size: 'medium',
          variant: 'default',
        },
        testProps: {
          type: 'email',
          maxLength: 50,
          clearable: true,
          prefix: '👤',
          suffix: '@',
        },
        validationRules: [
          { type: 'required', message: '用户名不能为空' },
          { type: 'minLength', value: 3, message: '用户名至少3个字符' },
        ],
        expectedBehavior: [
          '显示标签和占位符',
          '支持输入验证',
          '支持前缀和后缀图标',
          '支持清除按钮',
        ],
      },
      {
        type: 'SmartTextarea',
        name: '智能文本域',
        category: 'form',
        defaultProps: {
          label: '描述',
          placeholder: '请输入描述信息',
          rows: 4,
          size: 'medium',
        },
        testProps: {
          maxLength: 500,
          autoResize: true,
          resize: 'vertical',
        },
        validationRules: [{ type: 'maxLength', value: 500, message: '描述不能超过500字符' }],
        expectedBehavior: ['支持多行文本输入', '支持自动调整高度', '显示字符计数'],
      },
      {
        type: 'SmartSelect',
        name: '智能选择器',
        category: 'form',
        defaultProps: {
          label: '城市',
          placeholder: '请选择城市',
          options: [
            { label: '北京', value: 'beijing' },
            { label: '上海', value: 'shanghai' },
            { label: '广州', value: 'guangzhou' },
          ],
        },
        testProps: {
          multiple: true,
          searchable: true,
          clearable: true,
          allowCreate: true,
        },
        expectedBehavior: ['显示选项列表', '支持搜索过滤', '支持多选模式', '支持创建新选项'],
      },
      {
        type: 'SmartRadio',
        name: '智能单选框',
        category: 'form',
        defaultProps: {
          label: '性别',
          options: [
            { label: '男', value: 'male' },
            { label: '女', value: 'female' },
          ],
          direction: 'horizontal',
        },
        testProps: {
          variant: 'button',
          size: 'large',
        },
        expectedBehavior: ['显示单选选项', '支持按钮样式', '支持水平/垂直布局'],
      },
      {
        type: 'SmartCheckbox',
        name: '智能复选框',
        category: 'form',
        defaultProps: {
          label: '兴趣爱好',
          options: [
            { label: '阅读', value: 'reading' },
            { label: '运动', value: 'sports' },
            { label: '音乐', value: 'music' },
          ],
        },
        testProps: {
          checkAll: true,
          direction: 'vertical',
        },
        expectedBehavior: ['支持多选', '支持全选功能', '显示选中状态'],
      },
      {
        type: 'SmartDatePicker',
        name: '智能日期选择器',
        category: 'datetime',
        defaultProps: {
          label: '出生日期',
          format: 'YYYY-MM-DD',
          picker: 'date',
        },
        testProps: {
          showTime: true,
          range: true,
          timeFormat: 'HH:mm:ss',
        },
        expectedBehavior: ['显示日期选择面板', '支持时间选择', '支持日期范围选择'],
      },
      {
        type: 'SmartButton',
        name: '智能按钮',
        category: 'interaction',
        defaultProps: {
          children: '提交',
          type: 'submit',
          variant: 'primary',
          size: 'medium',
        },
        testProps: {
          loading: true,
          icon: '✓',
          iconPosition: 'left',
          block: true,
        },
        expectedBehavior: ['显示按钮文本', '支持加载状态', '支持图标显示', '支持块级布局'],
      },
    );

    // 显示组件测试用例
    this.testCases.push(
      {
        type: 'SmartTable',
        name: '智能表格',
        category: 'display',
        defaultProps: {
          columns: [
            { key: 'name', title: '姓名', dataIndex: 'name' },
            { key: 'age', title: '年龄', dataIndex: 'age' },
            { key: 'city', title: '城市', dataIndex: 'city' },
          ],
          dataSource: [
            { key: '1', name: '张三', age: 25, city: '北京' },
            { key: '2', name: '李四', age: 30, city: '上海' },
          ],
        },
        testProps: {
          pagination: true,
          bordered: true,
          size: 'small',
          rowSelection: { type: 'checkbox' },
        },
        expectedBehavior: ['显示表格数据', '支持分页', '支持行选择', '支持排序和筛选'],
      },
      {
        type: 'SmartDisplay',
        name: '智能显示',
        category: 'display',
        defaultProps: {
          value: '这是一段显示文本',
          copyable: true,
        },
        testProps: {
          ellipsis: true,
          mark: true,
          code: true,
          type: 'success',
        },
        expectedBehavior: ['显示文本内容', '支持复制功能', '支持文本省略', '支持不同样式'],
      },
      {
        type: 'SmartList',
        name: '智能列表',
        category: 'display',
        defaultProps: {
          dataSource: [
            { key: '1', title: '列表项1', description: '这是第一个列表项' },
            { key: '2', title: '列表项2', description: '这是第二个列表项' },
          ],
        },
        testProps: {
          bordered: true,
          split: true,
          size: 'large',
        },
        expectedBehavior: ['显示列表项', '支持分割线', '支持边框样式'],
      },
    );

    // 布局组件测试用例
    this.testCases.push(
      {
        type: 'SmartLayout',
        name: '智能布局',
        category: 'layout',
        defaultProps: {
          type: 'flex',
          direction: 'row',
          justify: 'start',
          align: 'start',
        },
        testProps: {
          gap: 16,
          padding: '20px',
          background: '#f5f5f5',
          borderRadius: 8,
        },
        expectedBehavior: ['支持弹性布局', '支持间距设置', '支持背景样式', '支持圆角边框'],
      },
      {
        type: 'SmartNavigation',
        name: '智能导航',
        category: 'interaction',
        defaultProps: {
          items: [
            { key: 'home', label: '首页', icon: '🏠' },
            { key: 'about', label: '关于', icon: 'ℹ️' },
            { key: 'contact', label: '联系', icon: '📞' },
          ],
          mode: 'horizontal',
        },
        testProps: {
          theme: 'dark',
          selectedKeys: ['home'],
          multiple: false,
        },
        expectedBehavior: ['显示导航菜单', '支持图标显示', '支持选中状态', '支持主题切换'],
      },
      {
        type: 'SmartForm',
        name: '智能表单',
        category: 'form',
        defaultProps: {
          layout: 'vertical',
          fields: [
            { name: 'username', label: '用户名', type: 'SmartInput' },
            { name: 'email', label: '邮箱', type: 'SmartInput' },
            { name: 'description', label: '描述', type: 'SmartTextarea' },
          ],
        },
        testProps: {
          labelCol: { span: 6 },
          wrapperCol: { span: 18 },
          validateTrigger: 'onChange',
        },
        expectedBehavior: ['渲染表单字段', '支持表单验证', '支持布局配置', '支持数据收集'],
      },
    );
  }

  /**
   * 获取所有测试用例
   */
  getAllTestCases(): ComponentTestCase[] {
    return this.testCases;
  }

  /**
   * 根据类型获取测试用例
   */
  getTestCaseByType(type: string): ComponentTestCase | undefined {
    return this.testCases.find((testCase) => testCase.type === type);
  }

  /**
   * 根据分类获取测试用例
   */
  getTestCasesByCategory(category: string): ComponentTestCase[] {
    return this.testCases.filter((testCase) => testCase.category === category);
  }

  /**
   * 生成测试页面Schema
   */
  generateTestPageSchema(): PageSchema {
    const components: Block[] = [];

    this.testCases.forEach((testCase, index) => {
      const component: Block = {
        id: `test-${testCase.type}-${index}`,
        type: testCase.type,
        position: { row: Math.floor(index / 3), column: index % 3 },
        props: {
          ...testCase.defaultProps,
          name: `${testCase.type}_${index}`,
          id: `test-${testCase.type}-${index}`,
          size: { width: 280, height: 120, span: 1 },
          validationRules: testCase.validationRules || [],
          style: {
            border: '1px solid #e0e0e0',
            borderRadius: '8px',
            padding: '16px',
            backgroundColor: '#ffffff',
          },
        },
      };

      components.push(component);
    });

    return {
      id: 'component-test-page',
      kind: 'home',
      name: '组件测试页面',
      title: '智能组件测试页面',
      description: '用于测试所有Smart组件的功能和属性',
      version: '1.0.0',
      components,
      layout: {
        type: 'grid',
        columns: 3,
        spacing: 16,
        padding: 24,
      },
      metadata: {
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        createdBy: 'AuraBoot Designer',
        tags: ['smart-components', 'test'],
      },
      meta: {
        title: '智能组件测试页面',
        description: '用于测试所有Smart组件的功能和属性',
        author: 'AuraBoot Designer',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    };
  }

  /**
   * 验证组件功能
   */
  validateComponent(
    type: string,
    props: Record<string, any>,
  ): {
    isValid: boolean;
    errors: string[];
    warnings: string[];
  } {
    const testCase = this.getTestCaseByType(type);
    if (!testCase) {
      return {
        isValid: false,
        errors: [`未找到组件类型 ${type} 的测试用例`],
        warnings: [],
      };
    }

    const errors: string[] = [];
    const warnings: string[] = [];

    // 检查必需属性
    const requiredProps = Object.keys(testCase.defaultProps);
    requiredProps.forEach((prop) => {
      if (!(prop in props)) {
        warnings.push(`缺少推荐属性: ${prop}`);
      }
    });

    // 检查属性类型
    Object.entries(props).forEach(([key, value]) => {
      if (key === 'children' && typeof value !== 'string' && typeof value !== 'object') {
        errors.push(`属性 ${key} 应该是字符串或React节点`);
      }
      if (key === 'disabled' && typeof value !== 'boolean') {
        errors.push(`属性 ${key} 应该是布尔值`);
      }
      if (key === 'size' && !['small', 'medium', 'large'].includes(value)) {
        warnings.push(`属性 ${key} 的值 ${value} 不在推荐范围内`);
      }
    });

    return {
      isValid: errors.length === 0,
      errors,
      warnings,
    };
  }

  /**
   * 生成测试报告
   */
  generateTestReport(
    results: Array<{
      type: string;
      success: boolean;
      errors: string[];
      warnings: string[];
      performance?: number;
    }>,
  ): string {
    const totalTests = results.length;
    const successfulTests = results.filter((r) => r.success).length;
    const failedTests = totalTests - successfulTests;

    let report = `# 智能组件测试报告\n\n`;
    report += `## 测试概览\n`;
    report += `- 总测试数: ${totalTests}\n`;
    report += `- 成功: ${successfulTests}\n`;
    report += `- 失败: ${failedTests}\n`;
    report += `- 成功率: ${((successfulTests / totalTests) * 100).toFixed(2)}%\n\n`;

    report += `## 详细结果\n\n`;
    results.forEach((result) => {
      const status = result.success ? '✅' : '❌';
      report += `### ${status} ${result.type}\n`;

      if (result.errors.length > 0) {
        report += `**错误:**\n`;
        result.errors.forEach((error) => {
          report += `- ${error}\n`;
        });
      }

      if (result.warnings.length > 0) {
        report += `**警告:**\n`;
        result.warnings.forEach((warning) => {
          report += `- ${warning}\n`;
        });
      }

      if (result.performance) {
        report += `**性能:** ${result.performance}ms\n`;
      }

      report += `\n`;
    });

    return report;
  }
}

export default ComponentTestSuite;
