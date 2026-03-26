/**
 * Command Toolbar Component
 *
 * 提供撤销、重做等命令操作的工具栏
 */

import React, { useState, useEffect, useCallback } from 'react';
import { getCommandManager, CommandEventType } from '~/studio/services/managers';

/**
 * 工具栏状态接口
 */
interface ToolbarState {
  canUndo: boolean;
  canRedo: boolean;
  historySize: number;
}

/**
 * 命令工具栏组件
 */
export const CommandToolbar: React.FC = () => {
  const [state, setState] = useState<ToolbarState>({
    canUndo: false,
    canRedo: false,
    historySize: 0,
  });

  const commandManager = getCommandManager();

  // 更新工具栏状态
  const updateState = useCallback(() => {
    const history = commandManager.getHistory();
    setState({
      canUndo: commandManager.canUndo(),
      canRedo: commandManager.canRedo(),
      historySize: history.commands.length,
    });
  }, [commandManager]);

  // 监听命令事件
  useEffect(() => {
    const handleCommandEvent = (event: any) => {
      updateState();
    };

    // 监听所有命令事件
    commandManager.on(CommandEventType.COMMAND_EXECUTED, handleCommandEvent);
    commandManager.on(CommandEventType.COMMAND_UNDONE, handleCommandEvent);
    commandManager.on(CommandEventType.COMMAND_REDONE, handleCommandEvent);
    commandManager.on(CommandEventType.HISTORY_CHANGED, handleCommandEvent);

    // 初始化状态
    updateState();

    return () => {
      commandManager.off(CommandEventType.COMMAND_EXECUTED, handleCommandEvent);
      commandManager.off(CommandEventType.COMMAND_UNDONE, handleCommandEvent);
      commandManager.off(CommandEventType.COMMAND_REDONE, handleCommandEvent);
      commandManager.off(CommandEventType.HISTORY_CHANGED, handleCommandEvent);
    };
  }, [commandManager, updateState]);

  // 撤销操作
  const handleUndo = useCallback(async () => {
    try {
      await commandManager.undo();
    } catch (error) {
      console.error('撤销操作失败:', error);
    }
  }, [commandManager]);

  // 重做操作
  const handleRedo = useCallback(async () => {
    try {
      await commandManager.redo();
    } catch (error) {
      console.error('重做操作失败:', error);
    }
  }, [commandManager]);

  // 清空历史
  const handleClearHistory = useCallback(() => {
    try {
      commandManager.clear();
    } catch (error) {
      console.error('清空历史失败:', error);
    }
  }, [commandManager]);

  return (
    <div className="flex items-center space-x-2 border-b border-gray-200 bg-white p-2">
      {/* 撤销按钮 */}
      <button
        onClick={handleUndo}
        disabled={!state.canUndo}
        className={`flex items-center rounded-md px-3 py-1.5 text-sm font-medium ${
          state.canUndo
            ? 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            : 'cursor-not-allowed bg-gray-50 text-gray-400'
        } `}
        title="撤销 (Ctrl+Z)"
      >
        <svg className="mr-1 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M3 10h10a8 8 0 018 8v2M3 10l6 6m-6-6l6-6"
          />
        </svg>
        撤销
      </button>

      {/* 重做按钮 */}
      <button
        onClick={handleRedo}
        disabled={!state.canRedo}
        className={`flex items-center rounded-md px-3 py-1.5 text-sm font-medium ${
          state.canRedo
            ? 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            : 'cursor-not-allowed bg-gray-50 text-gray-400'
        } `}
        title="重做 (Ctrl+Y)"
      >
        <svg className="mr-1 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M21 10H11a8 8 0 00-8 8v2m18-10l-6-6m6 6l-6 6"
          />
        </svg>
        重做
      </button>

      {/* 分隔线 */}
      <div className="h-6 w-px bg-gray-300" />

      {/* 清空历史按钮 */}
      <button
        onClick={handleClearHistory}
        disabled={state.historySize === 0}
        className={`flex items-center rounded-md px-3 py-1.5 text-sm font-medium ${
          state.historySize > 0
            ? 'bg-red-50 text-red-700 hover:bg-red-100'
            : 'cursor-not-allowed bg-gray-50 text-gray-400'
        } `}
        title="清空历史记录"
      >
        <svg className="mr-1 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
          />
        </svg>
        清空
      </button>

      {/* 历史记录信息 */}
      <div className="ml-4 flex items-center text-xs text-gray-500">
        <span>历史: {state.historySize}</span>
      </div>
    </div>
  );
};

export default CommandToolbar;

/**
 * 快捷键处理Hook
 */
export const useCommandShortcuts = () => {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      // Ctrl+Z 撤销
      if (event.ctrlKey && event.key === 'z' && !event.shiftKey) {
        event.preventDefault();
        getCommandManager().undo();
      }

      // Ctrl+Y 或 Ctrl+Shift+Z 重做
      if (
        (event.ctrlKey && event.key === 'y') ||
        (event.ctrlKey && event.shiftKey && event.key === 'z')
      ) {
        event.preventDefault();
        getCommandManager().redo();
      }
    };

    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, []);
};
