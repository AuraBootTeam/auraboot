import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ExclusiveGatewayEditor } from '../ExclusiveGatewayEditor';
import { RuleCenterBindingSection } from '../RuleCenterBindingSection';

describe('RuleCenterBindingSection', () => {
  it('creates a BPM-scoped default binding when enabled', () => {
    const onToggle = vi.fn();
    const onChange = vi.fn();

    render(
      <RuleCenterBindingSection
        title="规则中心路由"
        enabledLabel="Use Rule Center"
        enabled={false}
        mode="combined"
        consumerCode="approval_process"
        consumerNodeId="gateway_route"
        testId="rule-center-section"
        onToggle={onToggle}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByTestId('rule-center-section-toggle'));

    expect(onToggle).toHaveBeenCalledWith(
      true,
      expect.objectContaining({
        consumerType: 'BPM',
        consumerCode: 'approval_process',
        consumerNodeId: 'gateway_route',
        bindingKind: 'DECISION_REF',
        enabled: true,
        decisionBinding: expect.objectContaining({
          decisionCode: 'approval_routing',
          versionPolicy: 'LATEST_PUBLISHED',
        }),
        conditionSpec: expect.objectContaining({
          root: expect.objectContaining({ type: 'group', op: 'AND' }),
        }),
      }),
    );
  });

  it('keeps BPM consumer metadata when the embedded block changes', () => {
    const onChange = vi.fn();

    render(
      <RuleCenterBindingSection
        title="规则中心分派"
        enabledLabel="Use Rule Center"
        enabled
        mode="decision"
        value={{
          consumerType: 'BPM',
          consumerCode: 'approval_process',
          consumerNodeId: 'task_assign',
          bindingKind: 'DECISION_REF',
          decisionBinding: {
            decisionCode: 'task_assignee',
            versionPolicy: 'LATEST_PUBLISHED',
            inputMappings: [],
            outputMappings: [],
            fallbackPolicy: { mode: 'FAIL_CLOSED' },
            traceMode: 'SAMPLED',
            enabled: true,
          },
          enabled: true,
        }}
        consumerCode="approval_process"
        consumerNodeId="task_assign"
        testId="rule-center-section"
        onToggle={vi.fn()}
        onChange={onChange}
      />,
    );

    fireEvent.change(screen.getByLabelText('version-policy'), {
      target: { value: 'ROLLOUT' },
    });

    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({
        consumerType: 'BPM',
        consumerCode: 'approval_process',
        consumerNodeId: 'task_assign',
        decisionBinding: expect.objectContaining({ versionPolicy: 'ROLLOUT' }),
      }),
    );
  });
});

describe('BPM gateway property editor rule-center integration', () => {
  it('stores gateway rule binding on the existing gateway config', () => {
    const onChange = vi.fn();

    render(
      <ExclusiveGatewayEditor
        config={{ name: 'Route' }}
        outgoingEdges={[]}
        processKey="approval_process"
        nodeId="gateway_route"
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByTestId('exclusivegateway-rule-binding-toggle'));

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        name: 'Route',
        ruleBinding: expect.objectContaining({
          consumerType: 'BPM',
          consumerCode: 'approval_process',
          consumerNodeId: 'gateway_route',
        }),
      }),
    );
  });
});
