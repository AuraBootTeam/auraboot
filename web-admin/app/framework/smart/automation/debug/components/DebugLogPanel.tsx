/**
 * DebugLogPanel - Timeline event log for debug session.
 */

import React, { useRef, useEffect } from 'react';
import { cn } from '~/utils/cn';
import { useDebugSession } from '../hooks/useDebugSession';
import type { DebugEvent } from '../types';

const eventTypeConfig: Record<string, { icon: string; color: string }> = {
  ACTION_STARTED: { icon: '▶', color: 'text-blue-500' },
  ACTION_COMPLETED: { icon: '✓', color: 'text-green-500' },
  ACTION_FAILED: { icon: '✗', color: 'text-red-500' },
  SESSION_PAUSED: { icon: '⏸', color: 'text-yellow-500' },
  SESSION_COMPLETED: { icon: '✓', color: 'text-green-600' },
  SESSION_STOPPED: { icon: '⏹', color: 'text-gray-500' },
};

function EventItem({ event }: { event: DebugEvent }) {
  const cfg = eventTypeConfig[event.eventType] || { icon: '•', color: 'text-gray-400' };

  let message: string = event.eventType;
  if (event.actionType) {
    message = `${event.eventType}: ${event.actionLabel || event.actionType}`;
  }
  if (event.actionIndex !== undefined) {
    message += ` [#${event.actionIndex}]`;
  }

  const time = event.timestamp ? new Date(event.timestamp).toLocaleTimeString() : '';

  return (
    <div className="flex items-start gap-2 px-3 py-1 text-xs hover:bg-gray-50">
      <span className={cn('shrink-0', cfg.color)}>{cfg.icon}</span>
      <span className="min-w-0 flex-1 break-words text-gray-700">{message}</span>
      {event.actionResult?.durationMs !== undefined && (
        <span className="shrink-0 text-gray-400">{event.actionResult.durationMs}ms</span>
      )}
      <span className="shrink-0 text-gray-300">{time}</span>
    </div>
  );
}

export function DebugLogPanel() {
  const events = useDebugSession((s) => s.events);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new events
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [events.length]);

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between border-b bg-gray-50 px-3 py-2 text-xs font-medium tracking-wide text-gray-600 uppercase">
        <span>Events</span>
        <span className="font-normal text-gray-400">{events.length}</span>
      </div>
      <div ref={scrollRef} className="flex-1 overflow-auto">
        {events.length === 0 ? (
          <p className="py-4 text-center text-xs text-gray-400">
            No events yet. Click Step to begin.
          </p>
        ) : (
          events
            .filter((e) => e.eventType !== 'connected' && e.eventType !== 'heartbeat')
            .map((event, i) => <EventItem key={i} event={event} />)
        )}
      </div>
    </div>
  );
}
