import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * A boolean flag that flips `true` on `trigger()` and auto-resets to `false`
 * after `ms`. Re-triggering restarts the timer (debounced reset). Cleans up on
 * unmount. Backs the quiet "已保存到当前视图" hint (standard §3) and similar
 * transient confirmations.
 */
export function useTransientFlag(ms = 2000): [boolean, () => void] {
  const [on, setOn] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const trigger = useCallback(() => {
    setOn(true);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setOn(false), ms);
  }, [ms]);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  return [on, trigger];
}
