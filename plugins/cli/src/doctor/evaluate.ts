/**
 * Pure aggregation for `aura doctor`. The command gathers facts (some require
 * the network / filesystem); this function turns those facts into a structured,
 * testable pass/fail report. Keeping it pure means the pass/fail policy is
 * covered by unit tests without touching a live backend.
 */

export interface DoctorFacts {
  skills: { installed: number; stale: number; total: number };
  hasToken: boolean;
  tenant: { ok: boolean; detail: string };
  backend: { reachable: boolean; detail: string };
}

export interface DoctorCheck {
  name: 'skills' | 'credentials' | 'tenant' | 'backend';
  ok: boolean;
  detail: string;
}

export interface DoctorReport {
  ok: boolean;
  checks: DoctorCheck[];
}

export function evaluateDoctor(facts: DoctorFacts): DoctorReport {
  const { skills } = facts;
  const skillsOk = skills.total > 0 && skills.installed === skills.total && skills.stale === 0;

  const checks: DoctorCheck[] = [
    {
      name: 'skills',
      ok: skillsOk,
      detail: `${skills.installed}/${skills.total} installed${skills.stale > 0 ? `, ${skills.stale} stale` : ''}`,
    },
    {
      name: 'credentials',
      ok: facts.hasToken,
      detail: facts.hasToken ? 'token present' : 'no token — run: aura login',
    },
    {
      name: 'tenant',
      ok: facts.tenant.ok,
      detail: facts.tenant.detail,
    },
    {
      name: 'backend',
      ok: facts.backend.reachable,
      detail: facts.backend.detail,
    },
  ];

  return { ok: checks.every((c) => c.ok), checks };
}
