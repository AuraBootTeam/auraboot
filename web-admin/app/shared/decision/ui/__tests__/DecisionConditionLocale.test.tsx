import { useState } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { PolicyRulesEditor, type PolicyRulesValue } from '../PolicyRulesEditor';
import { ConditionBuilder, type FieldOption } from '../ConditionBuilder';
import { ConditionTestRunPanel } from '../ConditionTestRunPanel';
import { group, cmp, path, lit, not, type GroupNode } from '../../ast/conditionAst';

const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: language.locale, t: (key: string) => key }),
}));
beforeEach(() => {
  language.locale = 'en-US';
});
afterEach(() => {
  vi.unstubAllGlobals();
});

const fields: FieldOption[] = [
  { scope: 'record', path: 'data.amount', label: 'User amount', dataType: 'decimal' },
];
const condition = group('AND', [
  cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(100, 'decimal')),
  not(cmp(path('record', 'data.amount', 'decimal'), 'LT', lit(0, 'decimal'))),
]);
function RuleHarness() {
  const [value, setValue] = useState<PolicyRulesValue>({
    matchMode: 'COLLECT_ALL',
    rules: [
      { ruleCode: 'R-1', ruleName: 'User-authored rule', priority: 100, enabled: true, condition },
    ],
  });
  return (
    <>
      <PolicyRulesEditor value={value} fields={fields} onChange={setValue} />
      <pre data-testid="locale-draft">{JSON.stringify(value)}</pre>
    </>
  );
}
const userFields: FieldOption[] = [
  {
    scope: 'record',
    path: 'data.applicant',
    label: 'User applicant',
    dataType: 'user',
    reference: { targetEntity: 'users', valueField: 'pid', displayField: 'displayName' },
  },
];
const userCondition = () =>
  group('AND', [cmp(path('record', 'data.applicant', 'user'), 'EQ', lit('', 'user'))]);
function ReferenceHarness() {
  const [value, setValue] = useState<GroupNode>(userCondition);
  return (
    <>
      <ConditionBuilder value={value} fields={userFields} onChange={setValue} />
      <pre data-testid="locale-reference">{JSON.stringify(value)}</pre>
    </>
  );
}

describe('Decision condition locale contracts', () => {
  it.each(['en-US', 'en-GB'])(
    'localizes rule and operator labels for %s while preserving authored rules on language changes',
    (locale) => {
      language.locale = locale;
      const { rerender } = render(<RuleHarness />);
      expect(screen.getByTestId('pre-add-rule')).toHaveTextContent('Add rule');
      expect(screen.getByLabelText('match-mode')).toHaveDisplayValue('Collect all matches');
      expect(screen.getByLabelText('rule-enabled-0')).toHaveTextContent('Enabled');
      expect(screen.getAllByTestId('cb-preview')[0]).toHaveTextContent('greater than');
      expect(screen.getAllByTestId('cb-preview')[0]).toHaveTextContent('NOT(');
      const before = JSON.parse(screen.getByTestId('locale-draft').textContent!);
      fireEvent.click(screen.getByTestId('pre-add-rule'));
      expect(screen.getByLabelText('rule-name-1')).toHaveValue('Rule 2');
      const added = JSON.parse(screen.getByTestId('locale-draft').textContent!);
      expect(added.rules[0]).toEqual(before.rules[0]);
      expect(added.rules[1]).toMatchObject({
        ruleCode: 'R-2',
        ruleName: 'Rule 2',
        priority: 200,
        enabled: true,
      });
      language.locale = 'zh-CN';
      rerender(<RuleHarness />);
      expect(JSON.parse(screen.getByTestId('locale-draft').textContent!)).toEqual(added);
      expect(screen.getByLabelText('rule-name-0')).toHaveValue('User-authored rule');
      expect(screen.getByLabelText('rule-name-1')).toHaveValue('Rule 2');
    },
  );

  it('preserves a nested AST, operator values, field search and edited literals across locale changes', () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <ConditionBuilder value={condition} fields={fields} onChange={onChange} />,
    );
    expect(screen.getByTestId('cb-add-group')).toHaveTextContent('Add condition group');
    expect(screen.getByLabelText('operator-0')).toHaveDisplayValue('greater than');
    fireEvent.change(screen.getByLabelText('condition-field-search'), {
      target: { value: 'User amount' },
    });
    language.locale = 'zh-CN';
    rerender(<ConditionBuilder value={condition} fields={fields} onChange={onChange} />);
    expect(screen.getByLabelText('condition-field-search')).toHaveValue('User amount');
    expect(screen.getByLabelText('operator-0')).toHaveValue('GT');
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('value-0'), { target: { value: '125' } });
    expect(onChange).toHaveBeenCalledWith(
      group('AND', [
        cmp(path('record', 'data.amount', 'decimal'), 'GT', lit('125', 'decimal')),
        not(cmp(path('record', 'data.amount', 'decimal'), 'LT', lit(0, 'decimal'))),
      ]),
    );
  });

  it('localizes all three preview truth states without changing the selected sample or contexts', () => {
    const samples = [
      { label: 'Matching sample', context: { record: { data: { amount: 200 } } } },
      { label: 'Nonmatching sample', context: { record: { data: { amount: 50 } } } },
      { label: 'Missing sample', context: { record: { data: {} } } },
    ];
    const before = JSON.stringify(samples);
    const { rerender } = render(
      <ConditionTestRunPanel condition={condition} samples={samples} fields={fields} />,
    );
    expect(screen.getByTestId('trp-result')).toHaveTextContent('Matched');
    expect(screen.getByTestId('trp-nl')).toHaveTextContent('AND');
    fireEvent.click(screen.getByTestId('sample-1'));
    expect(screen.getByTestId('trp-result')).toHaveTextContent('Not matched');
    expect(screen.getByTestId('trp-result')).toHaveAttribute('data-truth', 'FALSE');
    fireEvent.click(screen.getByTestId('sample-2'));
    expect(screen.getByTestId('trp-result')).toHaveTextContent('Unknown');
    expect(screen.getByTestId('trp-result')).toHaveAttribute('data-truth', 'UNKNOWN');
    language.locale = 'zh-CN';
    rerender(<ConditionTestRunPanel condition={condition} samples={samples} fields={fields} />);
    expect(screen.getByTestId('sample-2')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('trp-result')).toHaveAttribute('data-truth', 'UNKNOWN');
    expect(JSON.stringify(samples)).toBe(before);
  });

  it('localizes the reference picker and preserves selected raw user IDs and names on language changes', async () => {
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL) =>
        ({
          ok: true,
          json: async () =>
            String(input).includes('/search')
              ? { data: { records: [{ pid: 'user-contract', displayName: 'User-authored name' }] } }
              : { data: { pid: 'user-contract', displayName: 'User-authored name' } },
        }) as Response,
    );
    vi.stubGlobal('fetch', fetchMock);
    const { rerender } = render(<ReferenceHarness />);
    expect(screen.getByTestId('reference-value-trigger-0')).toHaveTextContent('Select User');
    fireEvent.click(screen.getByTestId('reference-value-trigger-0'));
    expect(screen.getByLabelText('reference-search-0')).toHaveAttribute(
      'placeholder',
      'Search User',
    );
    fireEvent.click(await screen.findByTestId('reference-value-option-0-user-contract'));
    await waitFor(() =>
      expect(screen.getByTestId('reference-value-trigger-0')).toHaveTextContent(
        'User-authored name',
      ),
    );
    const before = screen.getByTestId('locale-reference').textContent;
    expect(JSON.parse(before!).children[0].right).toEqual(lit('user-contract', 'user'));
    const calls = fetchMock.mock.calls.length;
    language.locale = 'zh-CN';
    rerender(<ReferenceHarness />);
    expect(screen.getByTestId('locale-reference').textContent).toBe(before);
    expect(screen.getByTestId('reference-value-trigger-0')).toHaveTextContent('User-authored name');
    expect(fetchMock.mock.calls.length).toBe(calls);
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/users/search?keyword=&size=20', {
      credentials: 'same-origin',
    });
  });

  it('shows reference request failure as an error rather than an empty result and preserves the AST', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('fixture request failure')));
    const onChange = vi.fn();
    render(<ConditionBuilder value={userCondition()} fields={userFields} onChange={onChange} />);
    fireEvent.click(screen.getByTestId('reference-value-trigger-0'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load reference options');
    expect(screen.queryByText('No matches')).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });
});
