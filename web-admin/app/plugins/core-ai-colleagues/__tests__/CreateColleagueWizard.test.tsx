import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

import { I18nProvider } from '~/contexts/I18nContext';
import CreateColleaguePage, { deriveAgentCode } from '../pages/ai/colleagues.new';

const postMock = vi.fn();

vi.mock('~/shared/services/http-client', () => ({
  post: (...args: unknown[]) => postMock(...args),
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));

vi.mock('react-router', () => ({ useNavigate: () => vi.fn() }));

function renderWizard() {
  return render(
    <I18nProvider>
      <CreateColleaguePage />
    </I18nProvider>,
  );
}

describe('deriveAgentCode', () => {
  it('slugs an ASCII name', () => {
    expect(deriveAgentCode('Customer Service Agent', 'abc')).toBe('customer_service_agent_abc');
  });

  it('falls back to "agent" when the name slugs to nothing', () => {
    // The bug this guards: a purely non-ASCII name produces an empty slug, and an empty
    // prefix would send agent_code="_abc" — or, worse, tempt a caller into sending "".
    expect(deriveAgentCode('小艾', 'abc')).toBe('agent_abc');
    expect(deriveAgentCode('（）', 'abc')).toBe('agent_abc');
  });

  it('collapses punctuation runs and trims the edges', () => {
    expect(deriveAgentCode('  Sales -- Bot!! ', 'x1')).toBe('sales_bot_x1');
  });

  it('caps the slug so the code fits agent_code varchar(100)', () => {
    const code = deriveAgentCode('a'.repeat(200), 'x1');
    expect(code.length).toBeLessThanOrEqual(100);
  });

  it('gives two colleagues built from one template distinct codes', () => {
    expect(deriveAgentCode('Support', 'aaa')).not.toBe(deriveAgentCode('Support', 'bbb'));
  });
});

describe('create colleague wizard', () => {
  beforeEach(() => {
    postMock.mockReset();
    postMock.mockResolvedValue({ code: '0', data: { pid: 'p1' } });
  });

  // The regression: the wizard posted name/agent_type/communication_style/status and no
  // agent_code, and agent_code is NOT NULL, so every creation from the UI failed with
  // "Field 'agent_code' is required". Asserting on the payload — not on the helper — is what
  // makes this test able to fail if the field is dropped from the request again.
  it('sends agent_code in the create payload', async () => {
    renderWizard();

    fireEvent.click(screen.getByTestId('wizard-template-skip'));
    fireEvent.change(screen.getByTestId('wizard-input-name'), {
      target: { value: 'Customer Service Agent' },
    });

    for (let i = 0; i < 5; i++) {
      const create = screen.queryByTestId('wizard-btn-create');
      if (create) {
        fireEvent.click(create);
        break;
      }
      const next = screen.queryByTestId('wizard-btn-next');
      if (!next) break;
      fireEvent.click(next);
    }

    await waitFor(() => expect(postMock).toHaveBeenCalled());

    const [url, payload] = postMock.mock.calls[0] as [string, Record<string, unknown>];
    expect(url).toBe('/api/dynamic/agent-definition/create');
    expect(payload.agent_code).toBeTruthy();
    expect(String(payload.agent_code)).toMatch(/^customer_service_agent_/);
  });
});
