export interface RefreshController {
  request: () => Promise<void>;
  schedule: (delayMs: number) => void;
  dispose: () => void;
}

/**
 * Runs at most one refresh at a time. A burst received while a request is in
 * flight is collapsed into one trailing refresh, and scheduled refreshes are
 * debounced. This keeps realtime notifications from amplifying expensive read
 * models into a request storm.
 */
export function createRefreshController(task: () => Promise<void>): RefreshController {
  let active: Promise<void> | null = null;
  let trailing = false;
  let disposed = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  const request = (): Promise<void> => {
    if (disposed) return Promise.resolve();
    if (active) {
      trailing = true;
      return active;
    }
    active = Promise.resolve()
      .then(task)
      .finally(() => {
        active = null;
        if (trailing && !disposed) {
          trailing = false;
          void request();
        }
      });
    return active;
  };

  return {
    request,
    schedule(delayMs) {
      if (disposed) return;
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        timer = null;
        void request();
      }, delayMs);
    },
    dispose() {
      disposed = true;
      trailing = false;
      if (timer) clearTimeout(timer);
      timer = null;
    },
  };
}

export interface LatestRequestGate {
  start: () => number;
  isCurrent: (requestId: number) => boolean;
  cancel: () => void;
}

/** Prevents a slow, older student-detail response from replacing a newer one. */
export function createLatestRequestGate(): LatestRequestGate {
  let current = 0;
  return {
    start: () => ++current,
    isCurrent: (requestId) => requestId === current,
    cancel: () => {
      current += 1;
    },
  };
}
