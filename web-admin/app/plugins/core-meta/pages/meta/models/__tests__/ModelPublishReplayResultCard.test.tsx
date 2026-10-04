import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { ModelPublishReplayResultCard } from '../ModelPublishReplayResultCard';
import type { ModelPublishReplayResult } from '~/shared/services/modelService';
import TEXT from '../modelPublishReplayPresentation.i18n.json';

const result: ModelPublishReplayResult = {
  step: { consumerType: 'PERMISSION_POLICY', sourceCode: 'model.orders.read', sourcePid: 'policy-90071992547409931234' },
  status: 'EXECUTED', executed: true, matched: false, traceId: 'trace-owned-long-identifier',
  message: 'DENY',
  outputs: { granted: false, memberId: '90071992547409931235', permissionCode: 'model.orders.read',
    candidateUserIds: ['90071992547409931236', '90071992547409931237'], candidateGroupIds: [],
    inputs: { secret: 'masked-value-must-stay-hidden' }, steps: [{ secret: 'step-value-must-stay-hidden' }] },
};
function show(value = result, locale = 'en-US') {
  return render(<MemoryRouter><ModelPublishReplayResultCard result={value} locale={locale} /></MemoryRouter>);
}

describe('ModelPublishReplayResultCard', () => {
  it.each([
    ['DECISION_VERSION', 'Decision review'],
    ['PERMISSION_POLICY', 'Permission review'],
    ['WORKFLOW_PROCESS', 'Workflow review'],
    ['SLA_RULE', 'SLA review'],
    ['AUTOMATION', 'Automation review'],
    ['EVENT_POLICY', 'Event policy'],
    ['NAMED_QUERY', 'Named query'],
  ])('presents the backend consumer %s with its business label', (consumerType, label) => {
    show({ step: { consumerType } });
    expect(screen.getByTestId('replay-business-summary')).toHaveTextContent(label);
    expect(screen.getByTestId('replay-business-summary')).not.toHaveTextContent(consumerType);
  });

  for (const locale of ['en-US', 'zh-CN']) {
    const language = locale === 'en-US' ? 'en' : 'zh-CN';
    it(locale + ' keeps business results readable and original identifiers in collapsed diagnostics', () => {
      show(result, locale);
      const summary = screen.getByTestId('replay-business-summary');
      expect(summary).toHaveTextContent(TEXT.consumerPermission[language]);
      expect(summary).toHaveTextContent(TEXT.valueNo[language]);
      expect(summary).toHaveTextContent('2 ' + TEXT.reviewers[language]);
      for (const value of [result.traceId!, result.step!.sourceCode!, result.step!.sourcePid!, ...result.outputs!.candidateUserIds as string[]]) {
        expect(summary).not.toHaveTextContent(value);
        expect(screen.getByTestId('replay-technical-details')).toHaveTextContent(value);
      }
      expect(screen.getByTestId('replay-technical-details')).not.toHaveAttribute('open');
      const href = screen.getByTestId('model-publish-replay-open-permission-trace').getAttribute('href')!;
      const params = new URL(href, 'https://example.test').searchParams;
      expect(params.get('traceId')).toBe(result.traceId);
      expect(params.get('callerType')).toBe('PERMISSION');
      expect(params.get('callerRef')).toBe('model.orders.read');
    });

    it(locale + ' never exposes object snapshots or hidden evaluation steps', () => {
      show(result, locale);
      expect(screen.getByTestId('replay-business-summary')).toHaveTextContent(TEXT.valueRecorded[language]);
      expect(document.body).not.toHaveTextContent('masked-value-must-stay-hidden');
      expect(document.body).not.toHaveTextContent('step-value-must-stay-hidden');
    });

    it(locale + ' retains an unknown field and value without treating its code as a business label', () => {
      show({ outputs: { futureField: 'future business value' } }, locale);
      const summary = screen.getByTestId('replay-business-summary');
      expect(summary).toHaveTextContent(TEXT.otherResult[language]);
      expect(summary).toHaveTextContent('future business value');
      expect(summary).not.toHaveTextContent('futureField');
      expect(screen.getByTestId('replay-technical-details')).toHaveTextContent('futureField');
      expect(screen.getByTestId('replay-technical-details')).toHaveTextContent('future business value');
    });

    it(locale + ' keeps fail-closed and false results instead of converting them into success', () => {
      show({ step: { consumerType: 'WORKFLOW_PROCESS' }, status: 'FAILED', executed: false, matched: false,
        outputs: { failClosed: true, conditionResult: 'FALSE', fallbackApplied: false },
        errors: ['workflow_rule_binding_fail_closed'] }, locale);
      const summary = screen.getByTestId('replay-business-summary');
      expect(summary).toHaveTextContent(TEXT.valueNotSatisfied[language]);
      expect(summary).toHaveTextContent(TEXT.failClosed[language]);
      expect(summary).toHaveTextContent(TEXT.valueYes[language]);
      expect(within(summary).getByRole('list')).toBeInTheDocument();
      expect(summary).not.toHaveTextContent('workflow_rule_binding_fail_closed');
    });
  }

  it('refreshes presentation when locale changes without changing the diagnostic identity', () => {
    const view = show(result);
    expect(screen.getByTestId('replay-business-summary')).toHaveTextContent('Permission review');
    view.rerender(<MemoryRouter><ModelPublishReplayResultCard result={result} locale="zh-CN" /></MemoryRouter>);
    expect(screen.getByTestId('replay-business-summary')).toHaveTextContent(TEXT.consumerPermission['zh-CN']);
    expect(screen.getByTestId('replay-business-summary')).not.toHaveTextContent('Permission review');
    expect(screen.getByTestId('replay-technical-details')).toHaveTextContent(result.traceId!);
    expect(screen.getByTestId('replay-technical-details')).not.toHaveAttribute('open');
  });
});
