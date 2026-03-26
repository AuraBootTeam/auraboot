/**
 * usePreview Hook
 *
 * Hook for managing preview panel state.
 *
 * @since 3.2.0
 */

import { useState, useCallback, useEffect } from 'react';
import type { PreviewMode, PreviewState } from './types';

interface UsePreviewOptions {
  /** Initial mode */
  initialMode?: PreviewMode;
  /** Trigger shortcut key */
  triggerKey?: string;
}

interface UsePreviewReturn {
  /** Whether preview is open */
  isOpen: boolean;
  /** Current mode */
  mode: PreviewMode;
  /** Mock data */
  mockData: Record<string, unknown>;
  /** Open preview */
  open: (mode?: PreviewMode) => void;
  /** Close preview */
  close: () => void;
  /** Toggle preview */
  toggle: () => void;
  /** Set mode */
  setMode: (mode: PreviewMode) => void;
  /** Set mock data */
  setMockData: (data: Record<string, unknown>) => void;
  /** Update mock data field */
  updateMockField: (path: string, value: unknown) => void;
}

/**
 * usePreview hook
 */
export function usePreview(options: UsePreviewOptions = {}): UsePreviewReturn {
  const { initialMode = 'panel', triggerKey = 'p' } = options;

  const [isOpen, setIsOpen] = useState(false);
  const [mode, setMode] = useState<PreviewMode>(initialMode);
  const [mockData, setMockData] = useState<Record<string, unknown>>({});

  // Handle keyboard shortcut (Ctrl+P)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key.toLowerCase() === triggerKey) {
        e.preventDefault();
        setIsOpen((prev) => !prev);
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [triggerKey]);

  const open = useCallback((newMode?: PreviewMode) => {
    if (newMode) {
      setMode(newMode);
    }
    setIsOpen(true);
  }, []);

  const close = useCallback(() => {
    setIsOpen(false);
  }, []);

  const toggle = useCallback(() => {
    setIsOpen((prev) => !prev);
  }, []);

  const updateMockField = useCallback((path: string, value: unknown) => {
    setMockData((prev) => ({
      ...prev,
      [path]: value,
    }));
  }, []);

  return {
    isOpen,
    mode,
    mockData,
    open,
    close,
    toggle,
    setMode,
    setMockData,
    updateMockField,
  };
}

export default usePreview;
