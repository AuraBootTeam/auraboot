/**
 * 错误展示组件
 */

export type ErrorType = 'timeout' | 'network' | 'server' | 'unknown';

interface ErrorDisplayProps {
  type: ErrorType;
  message?: string;
  onRetry?: () => void;
  onDismiss?: () => void;
}

const ERROR_MESSAGES: Record<ErrorType, { title: string; description: string }> = {
  timeout: {
    title: 'LLM 响应超时',
    description: '模型响应时间过长，请稍后重试',
  },
  network: {
    title: '网络连接失败',
    description: '请检查网络连接后重试',
  },
  server: {
    title: '服务器错误',
    description: '服务器遇到问题，请稍后重试',
  },
  unknown: {
    title: '未知错误',
    description: '发生了未知错误，请重试',
  },
};

export function ErrorDisplay({ type, message, onRetry, onDismiss }: ErrorDisplayProps) {
  const errorInfo = ERROR_MESSAGES[type];

  return (
    <div className="mb-4 rounded-lg border border-red-200 bg-red-50 p-4">
      <div className="flex items-start">
        <div className="flex-shrink-0">
          <svg
            className="h-5 w-5 text-red-400"
            xmlns="http://www.w3.org/2000/svg"
            viewBox="0 0 20 20"
            fill="currentColor"
          >
            <path
              fillRule="evenodd"
              d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
              clipRule="evenodd"
            />
          </svg>
        </div>
        <div className="ml-3 flex-1">
          <h3 className="text-sm font-medium text-red-800">{errorInfo.title}</h3>
          <div className="mt-2 text-sm text-red-700">
            <p>{message || errorInfo.description}</p>
          </div>
          {(onRetry || onDismiss) && (
            <div className="mt-4 flex space-x-3">
              {onRetry && (
                <button
                  onClick={onRetry}
                  className="text-sm font-medium text-red-800 hover:text-red-900"
                >
                  重试
                </button>
              )}
              {onDismiss && (
                <button
                  onClick={onDismiss}
                  className="text-sm font-medium text-red-600 hover:text-red-700"
                >
                  关闭
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * 内联错误提示（用于消息气泡中）
 */
export function InlineError({ message }: { message: string }) {
  return (
    <div className="flex items-center space-x-2 text-sm text-red-600">
      <svg
        className="h-4 w-4"
        xmlns="http://www.w3.org/2000/svg"
        viewBox="0 0 20 20"
        fill="currentColor"
      >
        <path
          fillRule="evenodd"
          d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z"
          clipRule="evenodd"
        />
      </svg>
      <span>{message}</span>
    </div>
  );
}
