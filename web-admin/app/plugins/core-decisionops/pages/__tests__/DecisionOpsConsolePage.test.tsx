import { describe, expect, it } from 'vitest';
import { loader } from '../DecisionOpsConsolePage';

describe('DecisionOpsConsolePage legacy route', () => {
  it('redirects /decision-ops to the DSL DecisionOps workspace', async () => {
    const response = await loader({} as Parameters<typeof loader>[0]);

    expect(response.status).toBe(302);
    expect(response.headers.get('Location')).toBe('/p/decisionops_rollouts');
  });
});
