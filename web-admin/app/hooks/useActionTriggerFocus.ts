import { useCallback, useEffect, useRef } from 'react';

/** Restore a disabled action trigger only when completion left focus on the body. */
export function useActionTriggerFocus(busy: boolean) {
  const trigger = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (busy || !trigger.current) return;
    const target = trigger.current;trigger.current = null;
    if (target.isConnected && document.activeElement === document.body && !target.matches(':disabled'))
      target.focus();
  }, [busy]);
  return useCallback(() => {
    trigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  }, []);
}
