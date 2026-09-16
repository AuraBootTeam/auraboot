/** Renew only while the user is actively using a visible browser tab. */
export function startActiveSessionRenewal(
  target: Window,
  doc: Document,
  renew: () => Promise<unknown>,
) {
  let lastActivity = Date.now();
  let lastCheck = 0;
  let inFlight = false;
  let stopped = false;
  const check = () => {
    const now = Date.now();
    if (stopped || doc.visibilityState !== 'visible' || inFlight ||
        now - lastActivity > 5 * 60_000 || now - lastCheck < 5 * 60_000) return;
    lastCheck = now;
    inFlight = true;
    void renew().catch(() => { /* Keep current session; normal auth errors remain authoritative. */ })
      .finally(() => { inFlight = false; });
  };
  const activity = () => { lastActivity = Date.now(); check(); };
  const visible = () => { if (doc.visibilityState === 'visible') activity(); };
  target.addEventListener('pointerdown', activity);
  target.addEventListener('keydown', activity);
  target.addEventListener('focus', activity);
  doc.addEventListener('visibilitychange', visible);
  const timer = target.setInterval(check, 60_000);
  check();
  return () => {
    stopped = true;
    target.clearInterval(timer);
    target.removeEventListener('pointerdown', activity);
    target.removeEventListener('keydown', activity);
    target.removeEventListener('focus', activity);
    doc.removeEventListener('visibilitychange', visible);
  };
}
