import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import NotificationRuleBuilder from '../NotificationRuleBuilder';

// NotificationRuleBuilder's section titles, option labels, presets and buttons were hardcoded
// Chinese; they now go through useSmartText('$i18n:notification_rule.*', '<English fallback>').
// With no i18n provider in the test env, st() returns the English fallback.
vi.mock('~/shared/services/http-client', () => ({
  get: () => Promise.resolve({ code: '0', data: { records: [] } }),
  post: vi.fn(),
  put: vi.fn(),
  ErrorCodes: { SUCCESS: '0' },
}));

describe('NotificationRuleBuilder i18n', () => {
  it('renders localized section titles and preset/quick-start (English fallback, no Chinese)', () => {
    render(<NotificationRuleBuilder initial={null} onSaved={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getByText('Basic info')).toBeInTheDocument();
    expect(screen.getByText('Trigger')).toBeInTheDocument();
    expect(screen.getByText('Condition')).toBeInTheDocument();
    expect(screen.getByText('Notification action')).toBeInTheDocument();
    expect(screen.getByText('Quick start — choose a template')).toBeInTheDocument();
    // preset labels localized
    expect(screen.getByText('Overdue payment reminder')).toBeInTheDocument();
    expect(screen.getByText('Create rule')).toBeInTheDocument();
    // no raw i18n key leak
    expect(screen.queryByText(/notification_rule\./)).not.toBeInTheDocument();
  });
});
