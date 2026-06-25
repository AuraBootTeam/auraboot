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

  it('registers the DecisionOps definition action custom block', () => {
    expect(getRuntimeComponentEntry('DecisionDefinitionActionsBlock')).toBeDefined();
    expect(getRuntimeComponentEntry('decision-definition-actions')).toBeDefined();
  });

  it('registers the DecisionOps EventPolicy action and designer custom blocks', () => {
    expect(getRuntimeComponentEntry('EventPolicyActionsBlock')).toBeDefined();
    expect(getRuntimeComponentEntry('decision-event-policy-actions')).toBeDefined();
    expect(getRuntimeComponentEntry('EventPolicyDesignerBlock')).toBeDefined();
    expect(getRuntimeComponentEntry('decision-event-policy-designer')).toBeDefined();
  });

  it('registers the DecisionOps execution-log trace custom block', () => {
    expect(getRuntimeComponentEntry('ExecutionLogTraceBlock')).toBeDefined();
    expect(getRuntimeComponentEntry('decision-execution-log-trace')).toBeDefined();
  });

  it('registers the DecisionOps decision-table workbench custom block', () => {
    expect(getRuntimeComponentEntry('DecisionTableWorkbenchBlock')).toBeDefined();
    expect(getRuntimeComponentEntry('decision-table-workbench')).toBeDefined();
  });

  it('registers the DecisionOps rule binding custom block', () => {
    expect(getRuntimeComponentEntry('DecisionRuleBindingBlock')).toBeDefined();
    expect(getRuntimeComponentEntry('decision-rule-binding')).toBeDefined();
  });

  it('registers the organization team members custom block', () => {
    expect(getRuntimeComponentEntry('TeamMembersBlock')).toBeDefined();
    expect(getRuntimeComponentEntry('team-members')).toBeDefined();
  });
});
