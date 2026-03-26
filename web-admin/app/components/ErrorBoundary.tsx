import React, { Component, type ErrorInfo, type ReactNode } from 'react';

const ERROR_TEXTS = {
  title: { 'zh-CN': '组件渲染错误', 'en-US': 'Component Rendering Error' },
  errorId: { 'zh-CN': '错误ID', 'en-US': 'Error ID' },
  errorMessage: { 'zh-CN': '错误信息', 'en-US': 'Error message' },
  retry: { 'zh-CN': '重试', 'en-US': 'Retry' },
  devInfo: { 'zh-CN': '开发者信息 (点击展开)', 'en-US': 'Developer Info (click to expand)' },
} as const;

type ErrorTextKey = keyof typeof ERROR_TEXTS;

function getLocale(): 'zh-CN' | 'en-US' {
  return typeof navigator !== 'undefined' && navigator.language?.startsWith('zh')
    ? 'zh-CN'
    : 'en-US';
}

function t(key: ErrorTextKey): string {
  return ERROR_TEXTS[key][getLocale()];
}

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
  onError?: (error: Error, errorInfo: ErrorInfo) => void;
}

interface State {
  hasError: boolean;
  error: Error | null;
  errorInfo: ErrorInfo | null;
  errorId: string | null;
}

/**
 * 智能错误边界组件
 * 专门用于捕获和分析 i18n 对象渲染错误
 */
export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null,
      errorId: null,
    };
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    // 生成唯一的错误ID用于追踪
    const errorId = `error_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;

    return {
      hasError: true,
      error,
      errorId,
    };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    this.setState({ errorInfo });

    // 检测是否是 i18n 对象渲染错误
    const isI18nError = this.detectI18nError(error, errorInfo);

    // 详细的错误日志
    console.group(`🚨 React Error Boundary - ${this.state.errorId}`);
    console.error('Error:', error);
    console.error('Error Info:', errorInfo);

    if (isI18nError) {
      console.warn('🌐 检测到可能的 i18n 对象渲染错误！');
      console.warn('💡 建议检查以下内容：');
      console.warn('   1. 是否有组件直接渲染了包含 {i18n, fallback} 的对象');
      console.warn('   2. 是否忘记使用 translateWithFallback() 函数');
      console.warn('   3. 检查组件的 props.label, props.placeholder 等属性');

      // 尝试从错误堆栈中提取组件信息
      const componentInfo = this.extractComponentInfo(errorInfo);
      if (componentInfo) {
        console.warn('🎯 可能的问题组件:', componentInfo);
      }
    }

    console.groupEnd();

    // 调用外部错误处理器
    if (this.props.onError) {
      this.props.onError(error, errorInfo);
    }

    // 在开发环境下，将错误信息发送到调试面板
    if (process.env.NODE_ENV === 'development') {
      this.notifyDebugPanel(error, errorInfo, isI18nError);
    }
  }

  /**
   * 检测是否是 i18n 相关的渲染错误
   */
  private detectI18nError(error: Error, errorInfo: ErrorInfo): boolean {
    const errorMessage = error.message.toLowerCase();
    const stackTrace = errorInfo.componentStack?.toLowerCase() || '';

    // 检测常见的 i18n 对象渲染错误模式
    const i18nErrorPatterns = [
      'objects are not valid as a react child',
      'cannot read property',
      'cannot read properties of undefined',
      "failed to execute 'createelement'",
    ];

    const i18nComponentPatterns = ['smart', 'i18n', 'translate', 'label', 'placeholder'];

    const hasErrorPattern = i18nErrorPatterns.some((pattern) => errorMessage.includes(pattern));

    const hasI18nComponent = i18nComponentPatterns.some((pattern) => stackTrace.includes(pattern));

    return hasErrorPattern && hasI18nComponent;
  }

  /**
   * 从错误信息中提取组件信息
   */
  private extractComponentInfo(errorInfo: ErrorInfo): string | null {
    const componentStack = errorInfo.componentStack;
    if (!componentStack) return null;
    const lines = componentStack.split('\n');

    // 查找第一个非 ErrorBoundary 的组件
    for (const line of lines) {
      const trimmed = line.trim();
      if (trimmed && !trimmed.includes('ErrorBoundary')) {
        const match = trimmed.match(/in (\w+)/);
        if (match) {
          return match[1];
        }
      }
    }

    return null;
  }

  /**
   * 通知调试面板
   */
  private notifyDebugPanel(error: Error, errorInfo: ErrorInfo, isI18nError: boolean) {
    // 发送自定义事件到调试面板
    const event = new CustomEvent('debug-panel-error', {
      detail: {
        errorId: this.state.errorId,
        error: {
          message: error.message,
          stack: error.stack,
        },
        errorInfo,
        isI18nError,
        timestamp: new Date().toISOString(),
      },
    });

    window.dispatchEvent(event);
  }

  /**
   * 重置错误状态
   */
  private handleReset = () => {
    this.setState({
      hasError: false,
      error: null,
      errorInfo: null,
      errorId: null,
    });
  };

  render() {
    if (this.state.hasError) {
      // 如果提供了自定义 fallback，使用它
      if (this.props.fallback) {
        return this.props.fallback;
      }

      // 默认的错误 UI
      return (
        <div className="error-boundary-container rounded-lg border border-red-200 bg-red-50 p-6">
          <div className="flex items-start space-x-3">
            <div className="flex-shrink-0">
              <svg className="h-5 w-5 text-red-400" viewBox="0 0 20 20" fill="currentColor">
                <path
                  fillRule="evenodd"
                  d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                  clipRule="evenodd"
                />
              </svg>
            </div>
            <div className="flex-1">
              <h3 className="text-sm font-medium text-red-800">{t('title')}</h3>
              <div className="mt-2 text-sm text-red-700">
                <p>
                  {t('errorId')}:{' '}
                  <code className="rounded bg-red-100 px-1">{this.state.errorId}</code>
                </p>
                {this.state.error && (
                  <p className="mt-1">
                    {t('errorMessage')}: {this.state.error.message}
                  </p>
                )}
              </div>
              <div className="mt-4">
                <button
                  onClick={this.handleReset}
                  className="rounded bg-red-100 px-3 py-1 text-sm font-medium text-red-800 transition-colors hover:bg-red-200"
                >
                  {t('retry')}
                </button>
              </div>
            </div>
          </div>

          {process.env.NODE_ENV === 'development' && this.state.errorInfo && (
            <details className="mt-4">
              <summary className="cursor-pointer text-sm font-medium text-red-800">
                {t('devInfo')}
              </summary>
              <pre className="mt-2 max-h-40 overflow-auto rounded bg-red-100 p-2 text-xs">
                {this.state.errorInfo.componentStack}
              </pre>
            </details>
          )}
        </div>
      );
    }

    return this.props.children;
  }
}

/**
 * 高阶组件：为组件添加错误边界
 */
export function withErrorBoundary<P extends object>(
  Component: React.ComponentType<P>,
  fallback?: ReactNode,
) {
  const WrappedComponent = (props: P) => (
    <ErrorBoundary fallback={fallback}>
      <Component {...props} />
    </ErrorBoundary>
  );

  WrappedComponent.displayName = `withErrorBoundary(${Component.displayName || Component.name})`;

  return WrappedComponent;
}

export default ErrorBoundary;
