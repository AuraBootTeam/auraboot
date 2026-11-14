// web-admin/app/flow-designer-sdk/core/useFlowAutoSave.ts
import { useCallback, useEffect, useRef, useState } from 'react';
import type { SaveStatus } from '~/shared/versioning/AutoSaveIndicator';

const DEFAULT_AUTO_SAVE_DELAY = 30_000; // 30 seconds

interface UseFlowAutoSaveOptions {
  onSave: () => Promise<void>;
  enabled: boolean;
  delay?: number;
  isDirty: boolean;
}

interface UseFlowAutoSaveReturn {
  saveStatus: SaveStatus;
  lastSaved: Date | null;
}

export function useFlowAutoSave({
  onSave,
  enabled,
  delay = DEFAULT_AUTO_SAVE_DELAY,
  isDirty,
}: UseFlowAutoSaveOptions): UseFlowAutoSaveReturn {
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle');
  const [lastSaved, setLastSaved] = useState<Date | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onSaveRef = useRef(onSave);
  onSaveRef.current = onSave;

  const clearTimer = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  // Auto-save on dirty change
  useEffect(() => {
    if (!enabled || !isDirty) {
      if (!isDirty && saveStatus === 'dirty') {
        setSaveStatus(lastSaved ? 'saved' : 'idle');
      }
      clearTimer();
      return;
    }

    setSaveStatus('dirty');
    clearTimer();

    timerRef.current = setTimeout(async () => {
      setSaveStatus('saving');
      try {
        await onSaveRef.current();
        setSaveStatus('saved');
        setLastSaved(new Date());
      } catch {
        setSaveStatus('error');
      }
    }, delay);

    return clearTimer;
  }, [enabled, isDirty, delay, clearTimer, saveStatus, lastSaved]);

  // Warn on unsaved changes before unload
  useEffect(() => {
    if (!enabled || !isDirty) return;

    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [enabled, isDirty]);

  return { saveStatus, lastSaved };
}
