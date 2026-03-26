/**
 * useDebugEvents - SSE subscription hook for real-time debug events.
 */

import { useEffect, useRef } from 'react';
import type { DebugEvent } from '../types';
import { useDebugSession } from './useDebugSession';
import { debugService } from '../../services/debugService';

/**
 * Subscribe to SSE debug events for the active session.
 * Automatically connects when a session is active and disconnects on cleanup.
 */
export function useDebugEvents() {
  const session = useDebugSession((s) => s.session);
  const addEvent = useDebugSession((s) => s.addEvent);
  const updateSessionFromEvent = useDebugSession((s) => s.updateSessionFromEvent);
  const eventSourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!session?.pid || !['paused', 'running'].includes(session.status)) {
      // Close existing connection if session is no longer active
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
        eventSourceRef.current = null;
      }
      return;
    }

    const url = debugService.getEventsUrl(session.pid);
    const eventSource = new EventSource(url);
    eventSourceRef.current = eventSource;

    const handleEvent = (eventType: string) => (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data) as DebugEvent;
        const event: DebugEvent = { ...data, eventType: eventType as DebugEvent['eventType'] };
        addEvent(event);
        updateSessionFromEvent(event);
      } catch {
        // Ignore parse errors
      }
    };

    // Subscribe to all event types
    eventSource.addEventListener('action_started', handleEvent('action_started'));
    eventSource.addEventListener('action_completed', handleEvent('action_completed'));
    eventSource.addEventListener('action_failed', handleEvent('action_failed'));
    eventSource.addEventListener('session_paused', handleEvent('session_paused'));
    eventSource.addEventListener('session_completed', handleEvent('session_completed'));
    eventSource.addEventListener('session_stopped', handleEvent('session_stopped'));

    eventSource.onerror = () => {
      eventSource.close();
      eventSourceRef.current = null;
    };

    return () => {
      eventSource.close();
      eventSourceRef.current = null;
    };
  }, [session?.pid, session?.status, addEvent, updateSessionFromEvent]);
}
