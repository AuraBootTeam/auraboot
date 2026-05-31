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
 * modal from the shared state.
 */
import React, { createContext, useCallback, useContext, useMemo, useState } from 'react';
import {
  AsyncTaskProgressModal,
  type AsyncTask,
} from '~/framework/meta/rendering/components/AsyncTaskProgressModal';

export interface AsyncTaskModalSink {
  activeTask: AsyncTask | null;
  setActiveTask: (task: AsyncTask | null) => void;
  clearActiveTask: () => void;
}

const AsyncTaskModalContext = createContext<AsyncTaskModalSink | null>(null);

/** Returns the shared sink if a provider is above in the tree, else null. */
export function useAsyncTaskModalSink(): AsyncTaskModalSink | null {
  return useContext(AsyncTaskModalContext);
}

export function AsyncTaskModalProvider({ children }: { children: React.ReactNode }) {
  const [activeTask, setActiveTask] = useState<AsyncTask | null>(null);
  const clearActiveTask = useCallback(() => setActiveTask(null), []);
  const value = useMemo<AsyncTaskModalSink>(
    () => ({ activeTask, setActiveTask, clearActiveTask }),
    [activeTask, clearActiveTask],
  );
  return (
    <AsyncTaskModalContext.Provider value={value}>{children}</AsyncTaskModalContext.Provider>
  );
}

/** Renders the single progress modal from the shared sink. Mount inside a provider. */
export function AsyncTaskModalHost() {
  const sink = useAsyncTaskModalSink();
  if (!sink || !sink.activeTask) return null;
  return (
    <AsyncTaskProgressModal
      task={sink.activeTask}
      onClose={sink.clearActiveTask}
      onBackground={sink.clearActiveTask}
    />
  );
}
