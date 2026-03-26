/**
 * 工具调用状态指示器
 * 显示工具调用的状态和结果
 */

import { useState } from 'react';
import type { ToolStatus } from '~/chat/types';
import { ResultContractRenderer } from '~/chat/components/ResultContractRenderer';

interface ToolStatusIndicatorProps {
  toolStatuses: ToolStatus[];
}

export function ToolStatusIndicator({ toolStatuses }: ToolStatusIndicatorProps) {
  if (toolStatuses.length === 0) {
    return null;
  }

  return (
    <div className="space-y-2">
      {toolStatuses.map((status, index) => (
        <ToolStatusCard key={index} status={status} />
      ))}
    </div>
  );
}

interface ToolStatusCardProps {
  status: ToolStatus;
}

function ToolStatusCard({ status }: ToolStatusCardProps) {
  const [isExpanded, setIsExpanded] = useState(false);

  const getStatusIcon = () => {
    switch (status.status) {
      case 'running':
        return <div className="h-4 w-4 animate-spin rounded-full border-b-2 border-blue-600"></div>;
      case 'completed':
        return (
          <svg
            className="h-4 w-4 text-green-600"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
          </svg>
        );
      case 'failed':
        return (
          <svg
            className="h-4 w-4 text-red-600"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M6 18L18 6M6 6l12 12"
            />
          </svg>
        );
    }
  };

  const getStatusColor = () => {
    switch (status.status) {
      case 'running':
        return 'bg-blue-50 border-blue-200';
      case 'completed':
        return 'bg-green-50 border-green-200';
      case 'failed':
        return 'bg-red-50 border-red-200';
    }
  };

  const getToolDisplayName = (toolName: string) => {
    const toolNames: Record<string, string> = {
      get_realtime_quote: '获取实时行情',
      get_financials: '查询财务数据',
      search_web: '搜索相关信息',
      calculate: '计算分析',
    };
    return toolNames[toolName] || toolName;
  };

  const hasResult = status.result && Object.keys(status.result).length > 0;

  return (
    <div className={`rounded-lg border p-3 ${getStatusColor()}`}>
      <div className="flex items-start space-x-3">
        {/* 状态图标 */}
        <div className="mt-0.5">{getStatusIcon()}</div>

        {/* 内容 */}
        <div className="min-w-0 flex-1">
          {/* 工具名称和消息 */}
          <div className="flex items-center space-x-2">
            <span className="font-medium text-gray-900">
              {getToolDisplayName(status.tool_name)}
            </span>
            {status.status === 'running' && (
              <span className="text-sm text-gray-600">{status.message}</span>
            )}
          </div>

          {/* 错误信息 */}
          {status.status === 'failed' && status.error && (
            <div className="mt-1 text-sm text-red-700">{status.error}</div>
          )}

          {/* Result rendered via ResultContract */}
          {status.status === 'completed' && hasResult && (
            <ResultContractRenderer result={status.result} toolName={status.tool_name} />
          )}
        </div>
      </div>
    </div>
  );
}
