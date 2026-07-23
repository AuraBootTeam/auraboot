import { describe, expect, it } from 'vitest';
import { evaluateDoctor, type DoctorFacts } from '../../src/doctor/evaluate.js';

const healthy: DoctorFacts = {
  skills: { installed: 6, stale: 0, total: 6 },
  hasToken: true,
  tenant: { ok: true, detail: 'tenant=acme (7)' },
  backend: { reachable: true, detail: 'http://localhost:6443 up' },
};

const checkByName = (facts: DoctorFacts, name: string) =>
  evaluateDoctor(facts).checks.find((c) => c.name === name)!;

describe('evaluateDoctor', () => {
  it('reports ok:true when everything is healthy', () => {
    const report = evaluateDoctor(healthy);
    expect(report.ok).toBe(true);
    expect(report.checks.every((c) => c.ok)).toBe(true);
    expect(report.checks.map((c) => c.name)).toEqual(['skills', 'credentials', 'tenant', 'backend']);
  });

  it('fails skills when not fully installed or stale', () => {
    expect(checkByName({ ...healthy, skills: { installed: 3, stale: 0, total: 6 } }, 'skills').ok).toBe(false);
    expect(checkByName({ ...healthy, skills: { installed: 6, stale: 2, total: 6 } }, 'skills').ok).toBe(false);
  });

  it('fails credentials and tenant when there is no token', () => {
    const report = evaluateDoctor({
      ...healthy,
      hasToken: false,
      tenant: { ok: false, detail: 'no token' },
    });
    expect(report.ok).toBe(false);
    expect(report.checks.find((c) => c.name === 'credentials')!.ok).toBe(false);
    expect(report.checks.find((c) => c.name === 'tenant')!.ok).toBe(false);
  });

  it('fails overall when the backend is unreachable', () => {
    const report = evaluateDoctor({
      ...healthy,
      backend: { reachable: false, detail: 'ECONNREFUSED' },
    });
    expect(report.ok).toBe(false);
    expect(report.checks.find((c) => c.name === 'backend')!.ok).toBe(false);
  });
});
