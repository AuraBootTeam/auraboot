/**
 * DebugToolbar - Control buttons for debug session.
 * Step / Continue / Stop / Restart + status indicator.
 */

import React from 'react';
import { cn } from '~/utils/cn';
import { useSmartText } from '~/utils/i18n';
import { useDebugSession } from '../hooks/useDebugSession';
import { debugStatusConfig } from '../types';

export function DebugToolbar() {
  const st = useSmartText();
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
        data-testid="automation-debug-status"
      >
        {st(`$i18n:automation.debug.status.${session.status}`) || statusCfg.label}
      </span>

      {/* Progress */}
      <span className="text-xs text-gray-400" data-testid="automation-debug-progress">
        {session.currentActionIndex}/{session.totalActions}
      </span>

      <div className="mx-1 h-4 w-px bg-gray-600" />

      {/* Step */}
      <button
        onClick={step}
        disabled={!isPaused || loading}
        className="rounded bg-blue-600 px-3 py-1 text-xs font-medium hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-gray-600"
        title={st('$i18n:automation.debug.toolbar.step.title') || 'Execute next action (Step Over)'}
        data-testid="automation-debug-step"
      >
        {st('$i18n:automation.debug.toolbar.step') || 'Step'}
      </button>

      {/* Continue */}
      <button
        onClick={continueExecution}
        disabled={!isPaused || loading}
        className="rounded bg-green-600 px-3 py-1 text-xs font-medium hover:bg-green-700 disabled:cursor-not-allowed disabled:bg-gray-600"
        title={st('$i18n:automation.debug.toolbar.continue.title') || 'Continue until breakpoint'}
        data-testid="automation-debug-continue"
      >
        {st('$i18n:automation.debug.toolbar.continue') || 'Continue'}
      </button>

      {/* Restart */}
      <button
        onClick={restart}
        disabled={loading}
        className="rounded bg-yellow-600 px-3 py-1 text-xs font-medium hover:bg-yellow-700 disabled:cursor-not-allowed disabled:bg-gray-600"
        title={st('$i18n:automation.debug.toolbar.restart.title') || 'Restart from beginning'}
        data-testid="automation-debug-restart"
      >
        {st('$i18n:automation.debug.toolbar.restart') || 'Restart'}
      </button>

      {/* Stop */}
      <button
        onClick={stop}
        disabled={!isActive || loading}
        className="rounded bg-red-600 px-3 py-1 text-xs font-medium hover:bg-red-700 disabled:cursor-not-allowed disabled:bg-gray-600"
        title={st('$i18n:automation.debug.toolbar.stop.title') || 'Stop debugging'}
        data-testid="automation-debug-stop"
      >
        {st('$i18n:automation.debug.toolbar.stop') || 'Stop'}
      </button>

      <div className="flex-1" />

      {/* Exit debug mode */}
      <button
        onClick={exitDebugMode}
        className="px-3 py-1 text-xs text-gray-400 hover:text-white"
        data-testid="automation-debug-exit"
      >
        {st('$i18n:automation.debug.toolbar.exit') || 'Exit Debug'}
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
