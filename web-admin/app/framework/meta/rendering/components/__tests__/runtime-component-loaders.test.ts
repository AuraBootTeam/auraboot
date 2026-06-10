import { describe, expect, it } from 'vitest';
import { getRuntimeComponentEntry } from '../runtime-component-loaders';

describe('runtime component loaders', () => {
  it('registers the DecisionOps rollout monitor custom block', () => {
    expect(getRuntimeComponentEntry('DecisionRolloutMonitorBlock')).toBeDefined();
    expect(getRuntimeComponentEntry('decision-rollout-monitor')).toBeDefined();
  });

  it('registers the DecisionOps field impact custom block', () => {
    expect(getRuntimeComponentEntry('DecisionFieldImpactBlock')).toBeDefined();
    expect(getRuntimeComponentEntry('decision-field-impact')).toBeDefined();
  });

  it('registers the DecisionOps integration impact custom block', () => {
    expect(getRuntimeComponentEntry('DecisionIntegrationImpactBlock')).toBeDefined();
    expect(getRuntimeComponentEntry('decision-integration-impact')).toBeDefined();
  });
});
