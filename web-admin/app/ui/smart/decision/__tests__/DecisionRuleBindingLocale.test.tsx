import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DecisionRuleBindingBlock } from '../DecisionRuleBindingBlock';

const language = vi.hoisted(() => ({ locale: 'en-US' }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: language.locale }) }));
beforeEach(() => {
  language.locale = 'en-US';
});
const fields = [
  {
    scope: 'record' as const,
    path: 'data.amount',
    label: 'Authored amount',
    dataType: 'decimal' as const,
  },
];
const decisions = [
  {
    code: 'user_decision',
    name: 'Authored decision',
    outputs: [{ id: 'deadline', label: 'Authored deadline', dataType: 'integer' }],
  },
];
const props = {
  mode: 'decision' as const,
  initialDecisionCode: 'user_decision',
  fields,
  decisions,
  initialContextJson: '{"record":{"data":{"amount":125}}}',
};
function api() {
  return {
    listDefinitions: vi.fn(async () => []),
    getDecisionImpact: vi.fn(async () => ({
      decisionCode: 'user_decision',
      incoming: [],
      outgoing: [],
      risk: { blocking: false, summary: 'Server summary' },
    })),
    evaluate: vi.fn(async () => ({
      status: 'NOT_MATCHED' as const,
      matched: false,
      outputs: {},
      traceId: 'trace-user-01',
      unknownReasons: ['Server reason'],
    })),
  };
}

describe('Decision binding locale contracts', () => {
  it('preserves authored names that resemble translated platform defaults', () => {
    render(
      <DecisionRuleBindingBlock
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'approval_routing',
            decisions: [{ code: 'approval_routing', name: 'Approval routing' }],
          },
        }}
        api={api()}
      />,
    );
    expect(screen.getByLabelText('decision-code')).toHaveDisplayValue('Approval routing');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('Approval routing');
  });
  it.each(['en-US', 'en-GB'])(
    'localizes %s without rewriting drafts, search or API identities',
    async (locale) => {
      language.locale = locale;
      const service = api();
      const onChange = vi.fn();
      const view = <DecisionRuleBindingBlock block={{ props }} api={service} onChange={onChange} />;
      const { rerender } = render(view);
      await waitFor(() => expect(service.listDefinitions).toHaveBeenCalledTimes(1));
      expect(screen.getByLabelText('version-policy')).toHaveDisplayValue('Latest published');
      expect(screen.getByLabelText('fallback-mode')).toHaveDisplayValue('Block on error');
      fireEvent.change(screen.getByLabelText('version-policy'), { target: { value: 'ROLLOUT' } });
      fireEvent.click(screen.getByRole('button', { name: 'Add mapping' }));
      fireEvent.change(screen.getByLabelText('mapping-input-0'), { target: { value: 'amount' } });
      fireEvent.change(screen.getByLabelText('mapping-field-search-0'), {
        target: { value: 'Authored' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Add output' }));
      fireEvent.change(screen.getByLabelText('output-mapping-output-0'), {
        target: { value: 'deadline' },
      });
      fireEvent.change(screen.getByLabelText('output-mapping-kind-0'), {
        target: { value: 'PROCESS_VARIABLE' },
      });
      fireEvent.change(screen.getByLabelText('output-mapping-path-0'), {
        target: { value: 'result.deadline' },
      });
      const before =
        screen.getByLabelText('decision-binding-debug-json').getAttribute('value') ??
        (screen.getByLabelText('decision-binding-debug-json') as HTMLTextAreaElement).value;
      const calls = onChange.mock.calls.length;
      language.locale = 'zh-CN';
      rerender(<DecisionRuleBindingBlock block={{ props }} api={service} onChange={onChange} />);
      expect(screen.getByLabelText('version-policy')).toHaveDisplayValue('灰度发布');
      expect(screen.getByLabelText('mapping-field-search-0')).toHaveValue('Authored');
      expect(screen.getByLabelText('decision-binding-debug-json')).toHaveValue(before);
      expect(onChange).toHaveBeenCalledTimes(calls);
      expect(service.listDefinitions).toHaveBeenCalledTimes(1);
      expect(JSON.parse(before)).toMatchObject({
        decisionBinding: {
          decisionCode: 'user_decision',
          versionPolicy: 'ROLLOUT',
          inputMappings: [
            { input: 'amount', source: { kind: 'FIELD', scope: 'record', path: 'data.amount' } },
          ],
          outputMappings: [
            { output: 'deadline', target: { kind: 'PROCESS_VARIABLE', path: 'result.deadline' } },
          ],
          fallbackPolicy: { mode: 'FAIL_CLOSED' },
        },
      });
    },
  );
  it('localizes impact and test states while preserving server content, payload and trace destination', async () => {
    const service = api();
    render(<DecisionRuleBindingBlock block={{ props }} api={service} />);
    fireEvent.click(screen.getByLabelText('refresh-impact'));
    await waitFor(() =>
      expect(screen.getByTestId('decision-impact-summary')).toHaveTextContent('Server summary'),
    );
    expect(screen.getByTestId('decision-impact-summary')).toHaveTextContent('Ready to continue');
    fireEvent.click(screen.getByLabelText('run-decision-test'));
    await waitFor(() =>
      expect(screen.getByTestId('decision-test-result')).toHaveTextContent('Not matched'),
    );
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('Server reason');
    expect(screen.getByTestId('decision-test-open-trace')).toHaveAttribute(
      'href',
      '/p/decisionops_execution_logs?traceId=trace-user-01&decisionCode=user_decision&callerType=RULE_BINDING_PREVIEW',
    );
    expect(service.evaluate).toHaveBeenCalledWith({
      decisionCode: 'user_decision',
      binding: 'LATEST',
      callerType: 'RULE_BINDING_PREVIEW',
      callerRef: undefined,
      context: { record: { data: { amount: 125 } } },
    });
  });
  it('shows localized request failures without replacing server errors or claiming a successful test', async () => {
    const service = api();
    service.getDecisionImpact.mockRejectedValueOnce('network');
    service.evaluate.mockRejectedValueOnce(new Error('Server rejection'));
    const { rerender } = render(<DecisionRuleBindingBlock block={{ props }} api={service} />);
    fireEvent.click(screen.getByLabelText('refresh-impact'));
    await waitFor(() =>
      expect(screen.getByTestId('decision-impact-error')).toHaveTextContent('Request failed'),
    );
    fireEvent.click(screen.getByLabelText('run-decision-test'));
    await waitFor(() =>
      expect(screen.getByTestId('decision-test-error')).toHaveTextContent('Server rejection'),
    );
    expect(screen.queryByTestId('decision-test-result')).not.toBeInTheDocument();
    language.locale = 'zh-CN';
    rerender(<DecisionRuleBindingBlock block={{ props }} api={service} />);
    expect(screen.getByTestId('decision-impact-error')).toHaveTextContent('请求失败');
    expect(screen.getByTestId('decision-test-error')).toHaveTextContent('Server rejection');
    expect(service.getDecisionImpact).toHaveBeenCalledTimes(1);
    expect(service.evaluate).toHaveBeenCalledTimes(1);
  });
  it('localizes platform defaults and read-only summaries', () => {
    render(
      <DecisionRuleBindingBlock
        block={{ props: { mode: 'decision', variant: 'summary' } }}
        api={api()}
      />,
    );
    expect(screen.getByTestId('decision-binding-summary')).toHaveTextContent('Rule center binding');
    expect(screen.getByTestId('decision-binding-summary')).toHaveTextContent(
      'Leave approval assignment',
    );
    expect(screen.getByTestId('decision-binding-summary')).toHaveTextContent(
      'No input mappings configured',
    );
    expect(screen.queryByLabelText('version-policy')).not.toBeInTheDocument();
  });
});
