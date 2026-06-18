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
import { render, screen, fireEvent } from '@testing-library/react';
import { ServiceTaskEditor } from '../ServiceTaskEditor';
import { StartEventEditor, EndEventEditor } from '../EventEditor';
import { ExclusiveGatewayEditor } from '../ExclusiveGatewayEditor';
import { ParallelGatewayEditor } from '../ParallelGatewayEditor';
import { ReceiveTaskEditor } from '../ReceiveTaskEditor';

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
