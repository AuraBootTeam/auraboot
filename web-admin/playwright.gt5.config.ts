import base from './playwright.config';

/**
 * G-T5 browser golden config (host-first, isolated runtime auraboot_51).
 *
 * Same as the base config, but the `setup` project is scoped to the 00/01 setup specs
 * (bootstrap invariants + multi-role users) and excludes `02-test-pages`, which seeds a
 * showcase system_overview dashboard the BPM-designer golden does not need. The full
 * showcase seed (oss-reset-and-init.sh) is gated by the shared-host dormancy guard while
 * other worktrees are active, so we avoid it entirely — the BPM designer only needs auth.
 */
const cfg: any = { ...base };
cfg.projects = (base as any).projects.map((p: any) =>
  p.name === 'setup' ? { ...p, testMatch: /\/0[01]-.*\.spec\.ts$/ } : p,
);
export default cfg;
