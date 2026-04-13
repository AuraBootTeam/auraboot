/**
 * useShortcutHelp Hook
 *
 * Hook for managing shortcut help panel state.
 *
 * @since 3.2.0
 */

import { useState, useCallback, useEffect } from 'react';

interface UseShortcutHelpOptions {
  /** Trigger key combination */
  triggerKey?: string;
  /** Whether to use ? key */
  useQuestionMark?: boolean;
}

interface UseShortcutHelpReturn {
  /** Whether panel is open */
  isOpen: boolean;
  /** Open the panel */
  open: () => void;
  /** Close the panel */
  close: () => void;
  /** Toggle panel */
  toggle: () => void;
}

/**
 * useShortcutHelp hook
 */
export function useShortcutHelp(options: UseShortcutHelpOptions = {}): UseShortcutHelpReturn {
  const { triggerKey = '?', useQuestionMark = true } = options;
  const [isOpen, setIsOpen] = useState(false);

  // Handle keyboard shortcut
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Check for ? key (Shift + /)
      if (useQuestionMark && e.key === '?' && e.shiftKey) {
        e.preventDefault();
        setIsOpen((prev) => !prev);
        return;
      }

      // Check for custom trigger key
      if (triggerKey && e.key === triggerKey) {
        e.preventDefault();
        setIsOpen((prev) => !prev);
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [triggerKey, useQuestionMark]);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);
  const toggle = useCallback(() => setIsOpen((prev) => !prev), []);

  return { isOpen, open, close, toggle };
}

export default useShortcutHelp;
