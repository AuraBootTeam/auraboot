import { useCallback, useLayoutEffect, useRef } from 'react';
import { createSearchParams, type useSearchParams } from 'react-router';

type SearchParamsSetter = ReturnType<typeof useSearchParams>[1];
type Navigation = { key: string; action: 'POP' | 'PUSH' | 'REPLACE' };

/** Serialize list URL writes until the router acknowledges the latest intent. */
export function useSerializedSearchParamsUpdater(
  searchParams: URLSearchParams,
  setSearchParams: SearchParamsSetter,
  writesEnabledRef?: { current: boolean },
  navigation?: Navigation,
): SearchParamsSetter {
  const search = searchParams.toString();
  const latestSearchParamsRef = useRef(new URLSearchParams(search));
  const pendingWritesRef = useRef<string[]>([]);
  const locationKeyRef = useRef(navigation?.key);
  const locationKey = navigation?.key;
  const navigationAction = navigation?.action;

  // A passive effect can run after the next click has already written a newer
  // intent. Reconcile at commit time, before another interactive event.
  useLayoutEffect(() => {
    const locationChanged = locationKeyRef.current !== locationKey;
    locationKeyRef.current = locationKey;
    const pending = pendingWritesRef.current;

    // Back/forward is authoritative even when its query matches an older write.
    if (locationChanged && navigationAction === 'POP') {
      pending.length = 0;
    } else {
      const acknowledged = pending.lastIndexOf(search);
      if (acknowledged >= 0) {
        pending.splice(0, acknowledged + 1);
        if (pending.length > 0) return;
      } else {
        // A URL we did not write is an external navigation, not an acknowledgement.
        pending.length = 0;
      }
    }
    latestSearchParamsRef.current = new URLSearchParams(search);
  }, [search, locationKey, navigationAction]);

  return useCallback<SearchParamsSetter>(
    (nextInit, options) => {
      if (writesEnabledRef?.current === false) return;
      const resolved = typeof nextInit === 'function'
        ? nextInit(new URLSearchParams(latestSearchParamsRef.current))
        : nextInit;
      const next = createSearchParams(resolved);
      const encoded = next.toString();
      const pending = pendingWritesRef.current;
      if (pending.at(-1) !== encoded) pending.push(encoded);
      latestSearchParamsRef.current = new URLSearchParams(next);
      setSearchParams(next, options);
    },
    [setSearchParams, writesEnabledRef],
  );
}
