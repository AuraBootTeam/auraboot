import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { HttpMessageDetails, storedHttpBody } from '../HttpMessageDetails';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'en',
    t: (_key: string, _params: unknown, fallback: string) => fallback,
  }),
}));

describe('shared inbound and outbound message presentation', () => {
  it('renders persisted delivery JSON and plain-text responses as text, including HTML', () => {
    const { container } = render(
      <HttpMessageDetails
        request={storedHttpBody('{"order":"P001"}')}
        response={storedHttpBody('<script>unsafe()</script>')}
      />,
    );
    expect(screen.getByRole('region', { name: 'Request body' })).toHaveTextContent(
      '"order": "P001"',
    );
    expect(screen.getByRole('region', { name: 'Response body' })).toHaveTextContent(
      '<script>unsafe()</script>',
    );
    expect(container.querySelector('script')).toBeNull();
  });

  it('distinguishes an empty HTTP body, missing history and exceeded capture limit', () => {
    const { rerender } = render(
      <HttpMessageDetails request={storedHttpBody('')} response={storedHttpBody(null)} />,
    );
    expect(screen.getByText('This call has no body')).toBeVisible();
    expect(
      screen.getByText('Body was not captured; historical content is unavailable'),
    ).toBeVisible();
    rerender(<HttpMessageDetails request={{ state: 'truncated' }} response={{ state: 'empty' }} />);
    expect(
      screen.getByText('Body exceeds the capture limit; partial content was not stored'),
    ).toBeVisible();
  });
});
