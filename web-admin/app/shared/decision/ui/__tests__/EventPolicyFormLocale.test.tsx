import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EventPolicyListPage } from '../EventPolicyListPage';
import { eventPolicyFormText } from '../eventPolicyFormText';
import messages from '../eventPolicyForm.i18n.json';
import type { DecisionApi } from '../../api/decisionApi';

// The locale contract under test: the create-policy form placeholders must come
// from the eventPolicyForm catalog via useI18n().locale — never the raw field
// keys (policyCode / policyName / eventType / targetType / targetKey).
const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: language.locale,
    t: (key: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? key,
  }),
}));

function renderListPage(api: DecisionApi) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <EventPolicyListPage api={api} />
    </QueryClientProvider>,
  );
}

function apiWith(rows: unknown[]): DecisionApi {
  return {
    listPolicies: vi.fn(async () => rows),
  } as unknown as DecisionApi;
}

const PLACEHOLDER_KEYS = [
  'placeholderPolicyCode',
  'placeholderPolicyName',
  'placeholderEventType',
  'placeholderTargetType',
  'placeholderTargetKey',
] as const;

const BARE_KEYS = ['policyCode', 'policyName', 'eventType', 'targetType', 'targetKey'];

async function openEditor() {
  await waitFor(() => expect(screen.getByTestId('event-policy-list')).toBeInTheDocument());
  fireEvent.click(screen.getByTestId('epl-new-policy'));
  await waitFor(() => expect(screen.getByTestId('epl-editor')).toBeInTheDocument());
}

describe('EventPolicyForm locale contracts', () => {
  afterEach(() => {
    language.locale = 'zh-CN';
  });

  it('exposes zh-CN and en entries for every form placeholder', () => {
    for (const key of PLACEHOLDER_KEYS) {
      expect(messages[key]['zh-CN'], `${key} must have a zh-CN entry`).toBeTruthy();
      expect(messages[key]['en'], `${key} must have an en entry`).toBeTruthy();
    }
  });

  it('shows zh-CN placeholders in the create-policy form by default', async () => {
    language.locale = 'zh-CN';
    renderListPage(apiWith([]));
    await openEditor();

    expect(screen.getByLabelText('policy-code')).toHaveAttribute('placeholder', '策略编码');
    expect(screen.getByLabelText('policy-name')).toHaveAttribute('placeholder', '策略名称');
    expect(screen.getByLabelText('policy-event-type')).toHaveAttribute('placeholder', '事件类型');
    expect(screen.getByLabelText('policy-target-type')).toHaveAttribute('placeholder', '目标类型');
    expect(screen.getByLabelText('policy-target-key')).toHaveAttribute('placeholder', '目标键');
  });

  it.each(['en', 'en-US'])('localizes the placeholders through the catalog for %s', async (locale) => {
    language.locale = locale;
    renderListPage(apiWith([]));
    await openEditor();

    expect(screen.getByLabelText('policy-code')).toHaveAttribute('placeholder', 'Policy code');
    expect(screen.getByLabelText('policy-name')).toHaveAttribute('placeholder', 'Policy name');
    expect(screen.getByLabelText('policy-event-type')).toHaveAttribute('placeholder', 'Event type');
    expect(screen.getByLabelText('policy-target-type')).toHaveAttribute('placeholder', 'Target type');
    expect(screen.getByLabelText('policy-target-key')).toHaveAttribute('placeholder', 'Target key');
    for (const bare of BARE_KEYS) {
      expect(screen.getByTestId('epl-editor')).not.toHaveTextContent(bare);
    }
  });

  it('falls back to zh-CN for an unmapped locale instead of the bare field key', async () => {
    language.locale = 'fr-FR';
    renderListPage(apiWith([]));
    await openEditor();

    expect(screen.getByLabelText('policy-code')).toHaveAttribute('placeholder', '策略编码');
    expect(screen.getByLabelText('policy-code').getAttribute('placeholder')).not.toBe('policyCode');
  });

  it('resolves every key through the same helper the components use', () => {
    expect(eventPolicyFormText('placeholderPolicyCode', 'zh-CN')).toBe('策略编码');
    expect(eventPolicyFormText('placeholderPolicyCode', 'en')).toBe('Policy code');
    expect(eventPolicyFormText('placeholderTargetKey', 'zh-CN')).toBe('目标键');
    expect(eventPolicyFormText('placeholderTargetKey', 'en')).toBe('Target key');
  });
});
