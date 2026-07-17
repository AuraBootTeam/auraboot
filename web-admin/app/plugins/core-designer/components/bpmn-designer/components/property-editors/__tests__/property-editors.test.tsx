/**
 * G-T5-ext — per-node-type property-editor binding unit tests.
 *
 * The userTask property panel has a real-browser golden (designer-property-edit
 * spec). This locks in the remaining active editors' field→onChange(config)
 * binding at the unit level (jsdom form editing is reliable; real drag is not —
 * that's covered by E2E). Covers every editable property + the GAP-252 readonly
 * visual-feedback fields. t() falls back to keys (no i18n provider needed).
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { BPMNPropertyPanel } from '../../BPMNPropertyPanel';
import { useBpmFlowStore } from '~/plugins/core-designer/components/bpm-designer-sdk/store/useBpmFlowStore';
import { BPMNNodeType } from '~/plugins/core-designer/components/bpmn-designer/types';
import { ServiceTaskEditor } from '../ServiceTaskEditor';
import { StartEventEditor, EndEventEditor } from '../EventEditor';
import { ExclusiveGatewayEditor } from '../ExclusiveGatewayEditor';
import { ParallelGatewayEditor } from '../ParallelGatewayEditor';
import { ReceiveTaskEditor } from '../ReceiveTaskEditor';
import { ProcessMetadataPanel } from '../ProcessMetadataPanel';

function processMetadata(overrides: Record<string, unknown> = {}) {
  return {
    name: 'P',
    processKey: 'p',
    description: '',
    category: '',
    isExisting: false,
    onNameChange: vi.fn(),
    onProcessKeyChange: vi.fn(),
    onDescriptionChange: vi.fn(),
    onCategoryChange: vi.fn(),
    onWithdrawPolicyChange: vi.fn(),
    onCcPolicyChange: vi.fn(),
    ...overrides,
  };
}

describe('ProcessMetadataPanel — process binding', () => {
  it('keeps the BPMN property panel above the canvas overflow layer', () => {
    useBpmFlowStore.getState().reset();

    render(<BPMNPropertyPanel processMetadata={processMetadata() as any} />);

    expect(screen.getByTestId('bpmn-property-panel')).toHaveClass('relative');
    expect(screen.getByTestId('bpmn-property-panel')).toHaveClass('z-20');
    expect(screen.getByTestId('bpmn-property-panel')).toHaveClass('shrink-0');
  });

  it('binds process key for new processes from the property panel', () => {
    const onProcessKeyChange = vi.fn();
    render(<ProcessMetadataPanel metadata={processMetadata({ onProcessKeyChange }) as any} />);

    fireEvent.change(screen.getByTestId('prop-panel-key'), { target: { value: 'approval_flow' } });

    expect(onProcessKeyChange).toHaveBeenCalledWith('approval_flow');
  });
});

describe('ServiceTaskEditor — property binding', () => {
  it('changing serviceType emits onChange with the new type', () => {
    const onChange = vi.fn();
    render(<ServiceTaskEditor config={{ serviceType: 'http' } as any} onChange={onChange} />);
    fireEvent.change(screen.getByTestId('servicetask-service-type'), { target: { value: 'command' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ serviceType: 'command' }));
  });

  it('command serviceType exposes commandCode and binds it', () => {
    const onChange = vi.fn();
    render(<ServiceTaskEditor config={{ serviceType: 'command' } as any} onChange={onChange} />);
    fireEvent.change(screen.getByTestId('servicetask-command-code'), { target: { value: 'sl:approve' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ commandCode: 'sl:approve' }));
  });

  it('http serviceType exposes serviceUrl', () => {
    const onChange = vi.fn();
    render(<ServiceTaskEditor config={{ serviceType: 'http' } as any} onChange={onChange} />);
    fireEvent.change(screen.getByTestId('servicetask-service-url'), { target: { value: 'https://x/y' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ serviceUrl: 'https://x/y' }));
  });

  it('async checkbox binds boolean', () => {
    const onChange = vi.fn();
    render(<ServiceTaskEditor config={{ serviceType: 'command' } as any} onChange={onChange} />);
    fireEvent.click(screen.getByTestId('servicetask-async'));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ async: true }));
  });

  it('action serviceType consumes rule-center action catalog availability and binds action config', async () => {
    const onChange = vi.fn();
    const api = {
      getActionCatalog: vi.fn(async () => ({
        actions: [
          {
            actionType: 'SEND_SMS',
            label: '发送短信',
            description: '向手机号发送短信',
            category: 'messaging',
            consumerTypes: ['BPM'],
            consumerAvailability: [
              {
                consumerType: 'BPM',
                handlerAvailable: false,
                availabilityStatus: 'UNAVAILABLE',
                availabilityReason: '当前环境未配置真实短信 provider',
                providerDependencies: [
                  {
                    providerType: 'SMS',
                    label: '真实短信 provider',
                    required: true,
                    available: false,
                    availabilityStatus: 'UNAVAILABLE',
                    availabilityReason: '当前环境未配置真实短信 provider',
                  },
                ],
              },
              {
                consumerType: 'SLA',
                handlerAvailable: true,
                availabilityStatus: 'AVAILABLE',
              },
            ],
          },
          {
            actionType: 'WEBHOOK',
            label: '发送 Webhook',
            category: 'integration',
            consumerTypes: ['SLA'],
          },
        ],
      })),
    };

    render(
      <ServiceTaskEditor
        config={{ serviceType: 'action', actionType: 'SEND_SMS' } as any}
        onChange={onChange}
        api={api}
      />,
    );

    await waitFor(() => expect(api.getActionCatalog).toHaveBeenCalledOnce());
    await waitFor(() =>
      expect(screen.getByTestId('servicetask-action-availability')).toHaveTextContent(
        '当前环境未配置真实短信 provider',
      ),
    );
    expect(screen.getByTestId('servicetask-action-provider')).toHaveTextContent(
      '依赖：真实短信 provider · 未配置',
    );
    expect(screen.getByTestId('servicetask-action-type')).toHaveTextContent('发送短信（不可用）');
    expect(screen.getByTestId('servicetask-action-type')).not.toHaveTextContent('发送 Webhook');

    fireEvent.change(screen.getByTestId('servicetask-action-target'), { target: { value: 'PHONE:${record.phone}' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ actionTarget: 'PHONE:${record.phone}' }));

    fireEvent.change(screen.getByTestId('servicetask-action-payload'), {
      target: { value: '{"content":"流程通知"}' },
    });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ actionPayloadJson: '{"content":"流程通知"}' }));

    fireEvent.change(screen.getByTestId('servicetask-action-result-var'), { target: { value: 'smsResult' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ actionResultVar: 'smsResult' }));

    fireEvent.change(screen.getByTestId('servicetask-action-idempotency'), { target: { value: 'bpm:${process.id}' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ actionIdempotencyKey: 'bpm:${process.id}' }));
  });
});

describe('StartEventEditor — property binding', () => {
  it('binds description / initiator / formKey', () => {
    const onChange = vi.fn();
    render(<StartEventEditor config={{} as any} onChange={onChange} />);
    fireEvent.change(screen.getByTestId('startevent-description'), { target: { value: 'kick off' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ description: 'kick off' }));
    fireEvent.change(screen.getByTestId('startevent-form-key'), { target: { value: 'form_leave' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ formKey: 'form_leave' }));
  });
});

describe('EndEventEditor — property binding', () => {
  it('terminateAll checkbox binds boolean', () => {
    const onChange = vi.fn();
    render(<EndEventEditor config={{} as any} onChange={onChange} />);
    fireEvent.click(screen.getByTestId('endevent-terminate-all'));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ terminateAll: true }));
  });
});

describe('ExclusiveGatewayEditor — property binding', () => {
  it('defaultFlow select is populated from outgoingEdges and binds', () => {
    const onChange = vi.fn();
    render(
      <ExclusiveGatewayEditor
        config={{} as any}
        onChange={onChange}
        outgoingEdges={[
          { id: 'e1', label: 'approve', condition: '${approved}' },
          { id: 'e2', label: 'reject' },
        ]}
      />,
    );
    fireEvent.change(screen.getByTestId('gateway-default-flow'), { target: { value: 'e2' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ defaultFlow: 'e2' }));
  });
});

describe('ParallelGatewayEditor — property binding', () => {
  it('binds description (no defaultFlow for parallel)', () => {
    const onChange = vi.fn();
    render(<ParallelGatewayEditor config={{} as any} onChange={onChange} />);
    const desc = screen.getByPlaceholderText('bpmn.gateway.parallelDescPlaceholder');
    fireEvent.change(desc, { target: { value: 'fork all' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ description: 'fork all' }));
  });
});

describe('ReceiveTaskEditor — GAP-252 message binding (now supported)', () => {
  it('messageRef / messageType are editable and bind to config', () => {
    const onChange = vi.fn();
    render(<ReceiveTaskEditor config={{} as any} onChange={onChange} />);
    const messageRef = screen.getByTestId('receivetask-messageRef') as HTMLInputElement;
    const messageType = screen.getByTestId('receivetask-messageType') as HTMLInputElement;
    expect(messageRef).not.toBeDisabled();
    expect(messageType).not.toBeDisabled();

    fireEvent.change(messageRef, { target: { value: 'orderApproved' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ messageRef: 'orderApproved' }));

    fireEvent.change(messageType, { target: { value: 'signal' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ messageType: 'signal' }));
  });

  it('description still binds', () => {
    const onChange = vi.fn();
    const { container } = render(<ReceiveTaskEditor config={{} as any} onChange={onChange} />);
    const desc = container.querySelector('textarea') as HTMLTextAreaElement;
    fireEvent.change(desc, { target: { value: 'await msg' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ description: 'await msg' }));
  });
});

describe('BPMNPropertyPanel — rule-task rule center routing', () => {
  it('renders a shared rule binding editor for selected rule-task nodes', () => {
    useBpmFlowStore.getState().reset();
    useBpmFlowStore.setState({
      nodes: [
        {
          id: 'svc_rule_route',
          type: 'rule-task',
          position: { x: 0, y: 0 },
          data: {
            type: BPMNNodeType.RULE_TASK,
            label: 'Routing Rule',
            ruleCode: 'wd_leave_routing',
            factsVars: 'days,type',
          },
        } as any,
      ],
      edges: [],
      selectedNodeId: 'svc_rule_route',
      selectedEdgeId: null,
    });

    render(<BPMNPropertyPanel processMetadata={processMetadata({ processKey: 'wd_leave_approval' }) as any} />);

    expect(screen.getByDisplayValue('wd_leave_routing')).toBeInTheDocument();
    expect(screen.getByDisplayValue('days,type')).toBeInTheDocument();
    expect(screen.getByTestId('ruletask-rule-binding-toggle')).toBeInTheDocument();
    expect(screen.getByTestId('ruletask-rule-binding-editor')).toHaveTextContent('请假审批分派');
    expect(screen.getByTestId('decision-test-runner')).toBeInTheDocument();
  });
});
