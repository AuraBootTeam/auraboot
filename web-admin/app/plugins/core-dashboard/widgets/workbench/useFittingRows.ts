import { useLayoutEffect, useState, type RefObject } from 'react';

/**
 * How many whole rows of a list fit in the height its card gives it.
 *
 * Measured, never configured: the list's inner height and the pitch between its first two rows
 * (a row's height plus the gap under it). A card that is resized in the dashboard designer is
 * followed without a constant to keep in sync. Returns `null` when it cannot be measured - no
 * layout, as in tests - and the caller then shows every row.
 *
 * @param listRef the scroll container whose children are the rows
 * @param deps re-measure when these change (the row count, a loading flag)
 */
export function useFittingRows(listRef: RefObject<HTMLElement | null>, deps: unknown[]): number | null {
  const [fittingRows, setFittingRows] = useState<number | null>(null);

  useLayoutEffect(() => {
    const list = listRef.current;
    if (!list || typeof ResizeObserver === 'undefined') return;
    const measure = () => {
      const first = list.firstElementChild;
      if (!first) {
        setFittingRows(null);
        return;
      }
      const firstBox = first.getBoundingClientRect();
      const second = first.nextElementSibling;
      // The distance between two row tops is the pitch; the row's own height is the floor of it
      // (and all there is when a single row is rendered, or where boxes are not positioned).
      const step = second ? second.getBoundingClientRect().top - firstBox.top : 0;
      const pitch = Number.isFinite(step) && step > firstBox.height ? step : firstBox.height;
      const style = getComputedStyle(list);
      const padding = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.paddingBottom) || 0);
      setFittingRows(pitch > 0 ? Math.max(1, Math.floor((list.clientHeight - padding) / pitch)) : null);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(list);
    return () => observer.disconnect();
    // The caller lists what to re-measure on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return fittingRows;
}
