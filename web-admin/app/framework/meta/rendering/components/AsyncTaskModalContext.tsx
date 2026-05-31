/**
 * Shared state for the async-task progress modal.
 *
 * Multiple `useActionHandler` instances are mounted on a single page (e.g.
 * ListPageContent AND each ToolbarBlockRenderer call their own). A command
 * dispatched from the toolbar must surface its progress in the one modal mounted
 * for the page, so the `activeTask` state has to be shared rather than local to
 * each hook instance.
 *
 * `useActionHandler` consumes this context when a provider is present (falling
 * back to local state otherwise), and `AsyncTaskModalHost` renders the single
 * modal — or a compact chip when the user chooses 后台运行 — from the shared state.
 */
import React, { createContext, useCallback, useContext, useMemo, useState } from 'react';
import {
  AsyncTaskProgressModal,
  parseProgressMessage,
  type AsyncTask,
} from '~/framework/meta/rendering/components/AsyncTaskProgressModal';

export interface AsyncTaskModalSink {
  activeTask: AsyncTask | null;
  setActiveTask: (task: AsyncTask | null) => void;
  clearActiveTask: () => void;
  /** True when the user chose 后台运行 — render the chip instead of the modal. */
  minimized: boolean;
  setMinimized: (v: boolean) => void;
}

const AsyncTaskModalContext = createContext<AsyncTaskModalSink | null>(null);

/** Returns the shared sink if a provider is above in the tree, else null. */
export function useAsyncTaskModalSink(): AsyncTaskModalSink | null {
  return useContext(AsyncTaskModalContext);
}

export function AsyncTaskModalProvider({ children }: { children: React.ReactNode }) {
  const [activeTask, setActiveTaskRaw] = useState<AsyncTask | null>(null);
  const [minimized, setMinimized] = useState(false);
  // Opening a fresh task (null → non-null) always shows the modal, not the chip.
  const setActiveTask = useCallback((task: AsyncTask | null) => {
    setActiveTaskRaw((prev) => {
      if (!prev && task) setMinimized(false);
      return task;
    });
  }, []);
  const clearActiveTask = useCallback(() => {
    setActiveTaskRaw(null);
    setMinimized(false);
  }, []);
  const value = useMemo<AsyncTaskModalSink>(
    () => ({ activeTask, setActiveTask, clearActiveTask, minimized, setMinimized }),
    [activeTask, setActiveTask, clearActiveTask, minimized],
  );
  return (
    <AsyncTaskModalContext.Provider value={value}>{children}</AsyncTaskModalContext.Provider>
  );
}

/** Renders the modal, or a compact chip when minimized, from the shared sink. */
export function AsyncTaskModalHost() {
  const sink = useAsyncTaskModalSink();
  if (!sink || !sink.activeTask) return null;
  const task = sink.activeTask;
  if (sink.minimized) {
    const terminal = task.status === 'completed' || task.status === 'failed' || task.status === 'cancelled';
    const live = parseProgressMessage(task.progressMessage);
    const label = terminal
      ? task.status === 'completed'
        ? '导入完成'
        : '导入结束'
      : `导入中 ${typeof task.progress === 'number' ? task.progress : 0}%${
          live ? ` · ${live.ok}/${live.total}` : ''
        }`;
    return (
      <button
        type="button"
        data-testid="async-task-chip"
        onClick={() => sink.setMinimized(false)}
        className="fixed bottom-4 right-4 z-50 flex items-center gap-2 rounded-full border border-blue-200 bg-white px-4 py-2 text-sm font-medium text-blue-700 shadow-lg hover:bg-blue-50"
      >
        <span className={`inline-block h-2 w-2 rounded-full ${terminal ? 'bg-green-500' : 'animate-pulse bg-blue-500'}`} />
        {label}
      </button>
    );
  }
  return (
    <AsyncTaskProgressModal
      task={task}
      onClose={sink.clearActiveTask}
      onBackground={() => sink.setMinimized(true)}
    />
  );
}
