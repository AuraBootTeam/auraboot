/**
 * Action Debug Panel Component
 *
 * Panel for debugging action executions.
 *
 * @since 3.2.0
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { debugLogger } from './DebugLogger';
import {
  LOG_LEVEL_COLORS,
  EXECUTION_STATUS_COLORS,
  type DebugLogEntry,
  type ActionExecution,
  type LogLevel,
  type DebuggerState,
} from './types';

interface ActionDebugPanelProps {
  /** Whether panel is visible */
  isVisible?: boolean;
}

type TabView = 'logs' | 'executions';

/**
 * Action Debug Panel Component
 */
export const ActionDebugPanel: React.FC<ActionDebugPanelProps> = ({ isVisible = true }) => {
  const [tab, setTab] = useState<TabView>('logs');
  const [logs, setLogs] = useState<DebugLogEntry[]>([]);
  const [executions, setExecutions] = useState<ActionExecution[]>([]);
  const [state, setState] = useState<DebuggerState>(debugLogger.getState());
  const [selectedExecutionId, setSelectedExecutionId] = useState<string>();
  const [logFilter, setLogFilter] = useState<LogLevel | 'all'>('all');
  const [searchTerm, setSearchTerm] = useState('');

  // Subscribe to debug events
  useEffect(() => {
    if (!isVisible) return;

    const refresh = () => {
      setLogs([...debugLogger.getLogs()]);
      setExecutions([...debugLogger.getExecutions()]);
      setState(debugLogger.getState());
    };

    refresh();

    const unsubscribe = debugLogger.subscribe(() => {
      refresh();
    });

    return () => unsubscribe();
  }, [isVisible]);

  // Toggle debugger
  const toggleEnabled = useCallback(() => {
    debugLogger.setEnabled(!state.enabled);
    setState(debugLogger.getState());
  }, [state.enabled]);

  // Clear logs
  const handleClear = useCallback(() => {
    if (tab === 'logs') {
      debugLogger.clearLogs();
    } else {
      debugLogger.clearExecutions();
    }
    setLogs([...debugLogger.getLogs()]);
    setExecutions([...debugLogger.getExecutions()]);
  }, [tab]);

  // Export session
  const handleExport = useCallback(() => {
    const data = debugLogger.exportSession();
    const blob = new Blob([data], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `debug-session-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }, []);

  // Filter logs
  const filteredLogs = useMemo(() => {
    let result = logs;

    if (logFilter !== 'all') {
      result = result.filter((log) => log.level === logFilter);
    }

    if (searchTerm) {
      const term = searchTerm.toLowerCase();
      result = result.filter(
        (log) =>
          log.message.toLowerCase().includes(term) ||
          JSON.stringify(log.data).toLowerCase().includes(term),
      );
    }

    return result.slice(-100).reverse();
  }, [logs, logFilter, searchTerm]);

  if (!isVisible) return null;

  return (
    <div className="flex h-full flex-col bg-white">
      {/* Header */}
      <div className="flex-shrink-0 border-b border-gray-200 px-4 py-3">
        <div className="mb-2 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h3 className="text-sm font-semibold text-gray-900">Action 调试器</h3>
            <span
              className={`rounded-full px-1.5 py-0.5 text-[10px] ${state.enabled ? 'bg-green-100 text-green-600' : 'bg-gray-100 text-gray-500'} `}
            >
              {state.enabled ? '运行中' : '已停止'}
            </span>
          </div>

          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={toggleEnabled}
              className={`rounded px-2 py-1 text-xs transition-colors ${state.enabled ? 'text-red-600 hover:bg-red-50' : 'text-green-600 hover:bg-green-50'} `}
            >
              {state.enabled ? '停止' : '启动'}
            </button>
            <button
              type="button"
              onClick={handleClear}
              className="rounded px-2 py-1 text-xs text-gray-600 hover:bg-gray-100"
            >
              清空
            </button>
            <button
              type="button"
              onClick={handleExport}
              className="rounded px-2 py-1 text-xs text-gray-600 hover:bg-gray-100"
            >
              导出
            </button>
          </div>
        </div>

        {/* Tabs */}
        <div className="flex items-center gap-4">
          <button
            type="button"
            onClick={() => setTab('logs')}
            className={`border-b-2 pb-1 text-xs ${
              tab === 'logs'
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
          >
            日志 ({logs.length})
          </button>
          <button
            type="button"
            onClick={() => setTab('executions')}
            className={`border-b-2 pb-1 text-xs ${
              tab === 'executions'
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
          >
            执行 ({executions.length})
          </button>
        </div>
      </div>

      {/* Toolbar */}
      {tab === 'logs' && (
        <div className="flex-shrink-0 border-b border-gray-100 bg-gray-50 px-4 py-2">
          <div className="flex items-center gap-2">
            {/* Log level filter */}
            <select
              value={logFilter}
              onChange={(e) => setLogFilter(e.target.value as LogLevel | 'all')}
              className="rounded border border-gray-200 px-2 py-1 text-xs"
            >
              <option value="all">所有级别</option>
              <option value="error">错误</option>
              <option value="warn">警告</option>
              <option value="info">信息</option>
              <option value="debug">调试</option>
            </select>

            {/* Search */}
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="搜索日志..."
              className="flex-1 rounded border border-gray-200 px-2 py-1 text-xs"
            />
          </div>
        </div>
      )}

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        {!state.enabled && (
          <div className="p-4 text-center text-sm text-gray-500">
            <div className="mb-2 text-2xl">🔍</div>
            <p>调试器未启动</p>
            <p className="mt-1 text-xs">点击"启动"按钮开始记录</p>
          </div>
        )}

        {state.enabled && tab === 'logs' && <LogList logs={filteredLogs} />}

        {state.enabled && tab === 'executions' && (
          <ExecutionList
            executions={executions}
            selectedId={selectedExecutionId}
            onSelect={setSelectedExecutionId}
          />
        )}
      </div>

      {/* Status bar */}
      <div className="flex-shrink-0 border-t border-gray-200 bg-gray-50 px-4 py-2">
        <div className="flex items-center justify-between text-xs text-gray-500">
          <span>
            {state.enabled && debugLogger.getSession()
              ? `会话已运行 ${formatDuration(Date.now() - debugLogger.getSession()!.startTime)}`
              : '调试器已停止'}
          </span>
          <span>
            日志: {logs.length} | 执行: {executions.length}
          </span>
        </div>
      </div>
    </div>
  );
};

/**
 * Log list component
 */
interface LogListProps {
  logs: DebugLogEntry[];
}

const LogList: React.FC<LogListProps> = ({ logs }) => {
  if (logs.length === 0) {
    return <div className="p-4 text-center text-sm text-gray-400">暂无日志</div>;
  }

  return (
    <div className="divide-y divide-gray-50">
      {logs.map((log) => (
        <LogEntry key={log.id} log={log} />
      ))}
    </div>
  );
};

/**
 * Log entry component
 */
interface LogEntryProps {
  log: DebugLogEntry;
}

const LogEntry: React.FC<LogEntryProps> = ({ log }) => {
  const [expanded, setExpanded] = useState(false);
  const colors = LOG_LEVEL_COLORS[log.level];

  return (
    <div
      className={`cursor-pointer px-4 py-2 hover:bg-gray-50 ${colors.bg}`}
      onClick={() => setExpanded(!expanded)}
    >
      <div className="flex items-start gap-2">
        <span className={`${colors.text} text-xs`}>{colors.icon}</span>
        <span className="w-16 flex-shrink-0 text-[10px] text-gray-400">
          {formatTime(log.timestamp)}
        </span>
        <span className={`text-xs ${colors.text} flex-1`}>{log.message}</span>
      </div>

      {expanded && log.data && (
        <pre className="mt-2 ml-7 overflow-x-auto rounded bg-gray-100 p-2 text-[10px]">
          {JSON.stringify(log.data, null, 2)}
        </pre>
      )}

      {expanded && log.stack && (
        <pre className="mt-2 ml-7 overflow-x-auto rounded bg-red-50 p-2 text-[10px] text-red-600">
          {log.stack}
        </pre>
      )}
    </div>
  );
};

/**
 * Execution list component
 */
interface ExecutionListProps {
  executions: ActionExecution[];
  selectedId?: string;
  onSelect?: (id: string) => void;
}

const ExecutionList: React.FC<ExecutionListProps> = ({ executions, selectedId, onSelect }) => {
  if (executions.length === 0) {
    return <div className="p-4 text-center text-sm text-gray-400">暂无执行记录</div>;
  }

  return (
    <div className="divide-y divide-gray-100">
      {executions.map((execution) => (
        <ExecutionEntry
          key={execution.id}
          execution={execution}
          isSelected={selectedId === execution.id}
          onSelect={() => onSelect?.(execution.id)}
        />
      ))}
    </div>
  );
};

/**
 * Execution entry component
 */
interface ExecutionEntryProps {
  execution: ActionExecution;
  isSelected: boolean;
  onSelect?: () => void;
  depth?: number;
}

const ExecutionEntry: React.FC<ExecutionEntryProps> = ({
  execution,
  isSelected,
  onSelect,
  depth = 0,
}) => {
  const [expanded, setExpanded] = useState(false);
  const colors = EXECUTION_STATUS_COLORS[execution.status];

  return (
    <>
      <div
        className={`cursor-pointer px-4 py-2 transition-colors ${isSelected ? 'bg-blue-50' : 'hover:bg-gray-50'} `}
        style={{ paddingLeft: 16 + depth * 16 }}
        onClick={() => {
          onSelect?.();
          setExpanded(!expanded);
        }}
      >
        <div className="flex items-center gap-2">
          {/* Status icon */}
          <span
            className={`${colors.bg} ${colors.text} flex h-5 w-5 items-center justify-center rounded text-xs`}
          >
            {colors.icon}
          </span>

          {/* Action info */}
          <div className="min-w-0 flex-1">
            <div className="text-xs font-medium text-gray-700">
              {execution.actionLabel || execution.actionType}
            </div>
            <div className="text-[10px] text-gray-400">
              {execution.trigger.type} • {formatTime(execution.startTime)}
              {execution.duration !== undefined && ` • ${execution.duration}ms`}
            </div>
          </div>

          {/* Children indicator */}
          {execution.children && execution.children.length > 0 && (
            <span className="text-[10px] text-gray-400">{execution.children.length} 子操作</span>
          )}
        </div>

        {/* Details */}
        {expanded && (
          <div className="mt-2 space-y-2">
            {execution.input && (
              <div>
                <div className="mb-1 text-[10px] text-gray-500">输入参数</div>
                <pre className="overflow-x-auto rounded bg-gray-100 p-2 text-[10px]">
                  {JSON.stringify(execution.input, null, 2)}
                </pre>
              </div>
            )}

            {execution.output !== undefined && (
              <div>
                <div className="mb-1 text-[10px] text-gray-500">输出结果</div>
                <pre className="overflow-x-auto rounded bg-green-50 p-2 text-[10px] text-green-700">
                  {JSON.stringify(execution.output, null, 2)}
                </pre>
              </div>
            )}

            {execution.error && (
              <div>
                <div className="mb-1 text-[10px] text-gray-500">错误信息</div>
                <pre className="overflow-x-auto rounded bg-red-50 p-2 text-[10px] text-red-600">
                  {execution.error}
                </pre>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Children */}
      {expanded &&
        execution.children?.map((child) => (
          <ExecutionEntry key={child.id} execution={child} isSelected={false} depth={depth + 1} />
        ))}
    </>
  );
};

/**
 * Format timestamp to time string
 */
function formatTime(timestamp: number): string {
  const date = new Date(timestamp);
  return date.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/**
 * Format duration
 */
function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`;
}

export default ActionDebugPanel;
