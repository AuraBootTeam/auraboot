/**
 * AutomationDebugger - Main debugger container.
 * Combines DebugToolbar + FlowView + VariablePanel + LogPanel.
 */

import React from 'react';
import { DebugToolbar } from './DebugToolbar';
import { DebugVariablePanel } from './DebugVariablePanel';
import { DebugLogPanel } from './DebugLogPanel';
import { useDebugSession } from '../hooks/useDebugSession';
import { useDebugEvents } from '../hooks/useDebugEvents';
import { cn } from '~/utils/cn';
import type { ActionResult } from '../../services/automationService';

const actionStatusColors: Record<string, string> = {
  success: 'border-green-400 bg-green-50',
  failed: 'border-red-400 bg-red-50',
};

/** Action step list for the debugger (replaces flow view with a simpler step list) */
function DebugActionList() {
  const session = useDebugSession((s) => s.session);
  if (!session) return null;

  const results = session.actionResults || [];
  const resultMap = new Map<number, ActionResult>();
  for (const r of results) {
    resultMap.set(r.sequence, r);
  }

  // Generate action indices
  const indices = Array.from({ length: session.totalActions }, (_, i) => i);

  return (
    <div className="flex-1 overflow-auto p-4">
      <div className="space-y-2">
        {indices.map((index) => {
          const result = resultMap.get(index);
          const isCurrent = index === session.currentActionIndex;
          const isBreakpoint = session.breakpoints.includes(index);
          const isDone = index < session.currentActionIndex;

          let statusClass = 'border-gray-200 bg-white';
          if (result) {
            statusClass = actionStatusColors[result.status] || statusClass;
          }
          if (isCurrent && session.status === 'paused') {
            statusClass = 'border-yellow-400 bg-yellow-50 ring-2 ring-yellow-300';
          }

          return (
            <div
              key={index}
              className={cn(
                'flex items-center gap-3 rounded-lg border px-4 py-3 transition-all',
                statusClass,
              )}
            >
              {/* Breakpoint indicator */}
              <div className="w-3 shrink-0">
                {isBreakpoint && <span className="text-sm text-red-500">●</span>}
              </div>

              {/* Step number */}
              <span className="w-6 shrink-0 text-right font-mono text-sm text-gray-400">
                {index}
              </span>

              {/* Action info */}
              <div className="min-w-0 flex-1">
                {result ? (
                  <div>
                    <span className="text-sm font-medium text-gray-700">{result.actionType}</span>
                    {result.durationMs !== undefined && (
                      <span className="ml-2 text-xs text-gray-400">{result.durationMs}ms</span>
                    )}
                    {result.errorMessage && (
                      <p className="mt-0.5 truncate text-xs text-red-500">{result.errorMessage}</p>
                    )}
                  </div>
                ) : (
                  <span className="text-sm text-gray-400">
                    {isCurrent ? 'Next action →' : `Action #${index}`}
                  </span>
                )}
              </div>

              {/* Status icon */}
              <div className="shrink-0">
                {result?.status === 'success' && <span className="text-green-500">✓</span>}
                {result?.status === 'failed' && <span className="text-red-500">✗</span>}
                {isCurrent && session.status === 'paused' && !result && (
                  <span className="text-yellow-500">▶</span>
                )}
              </div>
            </div>
          );
        })}

        {session.totalActions === 0 && (
          <p className="py-8 text-center text-sm text-gray-400">
            No actions defined in this automation.
          </p>
        )}
      </div>
    </div>
  );
}

export function AutomationDebugger() {
  // Connect SSE events
  useDebugEvents();

  return (
    <div className="flex h-full flex-col">
      {/* Debug Toolbar */}
      <DebugToolbar />

      {/* Main content: 3-column layout */}
      <div className="flex min-h-0 flex-1">
        {/* Left: Action steps */}
        <div className="flex flex-1 flex-col overflow-hidden border-r">
          <div className="border-b bg-gray-50 px-3 py-2 text-xs font-medium tracking-wide text-gray-600 uppercase">
            Actions
          </div>
          <DebugActionList />
        </div>

        {/* Right sidebar: Variables + Events */}
        <div className="flex w-80 flex-col border-l bg-white">
          {/* Variables - top half */}
          <div className="flex-1 overflow-hidden border-b">
            <DebugVariablePanel />
          </div>

          {/* Events - bottom half */}
          <div className="flex-1 overflow-hidden">
            <DebugLogPanel />
          </div>
        </div>
      </div>
    </div>
  );
}
