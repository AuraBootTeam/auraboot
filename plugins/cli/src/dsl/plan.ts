import type { DslState } from './state.js';

/**
 * Risk levels for a reconcile plan (blueprint gap G-B):
 *   L0 no changes · L1 create-only · L2 updates to existing contracts ·
 *   L3 destroys (delete model / drop resource) — the highest risk dominates.
 *
 * L3 (and destructive changes generally) is what the platform approval gate
 * (§3A-G3) should require confirmation for.
 */
export type RiskLevel = 'L0' | 'L1' | 'L2' | 'L3';

export interface DslPlan {
  create: string[];
  update: string[];
  destroy: string[];
  riskLevel: RiskLevel;
  changed: boolean;
}

export function computePlan(desired: DslState, prior: DslState | null): DslPlan {
  const priorRes = prior?.resources ?? {};
  const desiredRes = desired.resources;

  const create: string[] = [];
  const update: string[] = [];
  const destroy: string[] = [];

  for (const key of Object.keys(desiredRes)) {
    if (!(key in priorRes)) create.push(key);
    else if (priorRes[key] !== desiredRes[key]) update.push(key);
  }
  for (const key of Object.keys(priorRes)) {
    if (!(key in desiredRes)) destroy.push(key);
  }

  const changed = create.length + update.length + destroy.length > 0;
  let riskLevel: RiskLevel = 'L0';
  if (destroy.length > 0) riskLevel = 'L3';
  else if (update.length > 0) riskLevel = 'L2';
  else if (create.length > 0) riskLevel = 'L1';

  return { create, update, destroy, riskLevel, changed };
}
