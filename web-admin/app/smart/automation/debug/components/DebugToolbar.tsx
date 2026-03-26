/**
 * DebugToolbar - Control buttons for debug session.
 * Step / Continue / Stop / Restart + status indicator.
 */

import React from 'react';
import { cn } from '~/utils/cn';
import { useDebugSession } from '../hooks/useDebugSession';
import { debugStatusConfig } from '../types';

export function DebugToolbar() {
  const { session, loading, step, continueExecution, stop, restart, exitDebugMode } =
    useDebugSession();

  if (!session) return null;

  const statusCfg = debugStatusConfig[session.status] || debugStatusConfig.PAUSED;
  const isActive = session.status === 'paused' || session.status === 'running';
  const isPaused = session.status === 'paused';

  return (
    <div className="flex items-center gap-2 bg-gray-800 px-3 py-2 text-sm text-white">
      {/* Status */}
      <span
        className={cn(
          'rounded px-2 py-0.5 text-xs font-medium',
          statusCfg.bgColor,
          statusCfg.color,
        )}
      >
        {statusCfg.label}
      </span>

      {/* Progress */}
      <span className="text-xs text-gray-400">
        {session.currentActionIndex}/{session.totalActions}
      </span>

      <div className="mx-1 h-4 w-px bg-gray-600" />

      {/* Step */}
      <button
        onClick={step}
        disabled={!isPaused || loading}
        className="rounded bg-blue-600 px-3 py-1 text-xs font-medium hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-gray-600"
        title="Execute next action (Step Over)"
      >
        Step
      </button>

      {/* Continue */}
      <button
        onClick={continueExecution}
        disabled={!isPaused || loading}
        className="rounded bg-green-600 px-3 py-1 text-xs font-medium hover:bg-green-700 disabled:cursor-not-allowed disabled:bg-gray-600"
        title="Continue until breakpoint"
      >
        Continue
      </button>

      {/* Restart */}
      <button
        onClick={restart}
        disabled={loading}
        className="rounded bg-yellow-600 px-3 py-1 text-xs font-medium hover:bg-yellow-700 disabled:cursor-not-allowed disabled:bg-gray-600"
        title="Restart from beginning"
      >
        Restart
      </button>

      {/* Stop */}
      <button
        onClick={stop}
        disabled={!isActive || loading}
        className="rounded bg-red-600 px-3 py-1 text-xs font-medium hover:bg-red-700 disabled:cursor-not-allowed disabled:bg-gray-600"
        title="Stop debugging"
      >
        Stop
      </button>

      <div className="flex-1" />

      {/* Exit debug mode */}
      <button onClick={exitDebugMode} className="px-3 py-1 text-xs text-gray-400 hover:text-white">
        Exit Debug
      </button>

      {/* Error */}
      {session.errorMessage && (
        <span className="max-w-64 truncate text-xs text-red-400" title={session.errorMessage}>
          {session.errorMessage}
        </span>
      )}
    </div>
  );
}
