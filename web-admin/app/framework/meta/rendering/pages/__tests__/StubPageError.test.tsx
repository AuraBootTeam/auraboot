import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StubPageError } from '../StubPageError';

/**
 * Item-3: an unconfigured stub page must fail loud with a diagnosable error,
 * NOT render the misleading empty shell. StubPageError is that explicit state.
 */
describe('StubPageError', () => {
  it('renders an explicit, diagnosable error with a stable testid', () => {
    render(<StubPageError pageKey="webhook_delivery_log_list" />);
    const el = screen.getByTestId('page-stub-error');
    expect(el).toBeInTheDocument();
  });

  it('names the page so an operator can locate it', () => {
    render(<StubPageError pageKey="webhook_delivery_log_list" />);
    expect(screen.getByTestId('page-stub-error')).toHaveTextContent(
      'webhook_delivery_log_list',
    );
  });

  it('states it is an unconfigured placeholder page', () => {
    render(<StubPageError pageKey="x_list" />);
    expect(screen.getByTestId('page-stub-error').textContent).toMatch(/未配置|占位/);
  });

  it('surfaces the rename-missed-derived-pageKey cause as remediation', () => {
    render(<StubPageError pageKey="qo_quote_common_list" />);
    expect(screen.getByTestId('page-stub-error').textContent).toMatch(/派生|改名|pageKey/i);
  });
});
