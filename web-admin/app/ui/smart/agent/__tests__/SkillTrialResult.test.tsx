import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SkillTrialResult } from '../SkillTrialResult';
const { view } = vi.hoisted(() => ({ view: vi.fn() }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en', t: (_: string, _params: unknown, fallback: string) => fallback }),
}));
vi.mock('~/plugins/core-aurabot/components-internal/ChatBiResultCard', () => ({
  ChatBiResultCard: ({ result }: any) => {
    view(result);
    return <div>Business table</div>;
  },
}));
describe('Skill trial business presentation', () => {
  it('shows query evidence without storage identity or serialized configuration columns', () => {
    const { container } = render(
      <SkillTrialResult
        result={{
          status: 'SUCCESS',
          sourceToolCode: 'list:customer',
          data: {
            records: [
              {
                pid: 'PRIVATE',
                tenant_id: 7,
                name: 'Current customer',
                settings: '{"private":true}',
              },
            ],
            total: 1,
          },
        }}
      />,
    );
    expect(screen.getByText('Business table')).toBeVisible();
    expect(view).toHaveBeenLastCalledWith(
      expect.objectContaining({ modelCode: 'customer', columns: ['name'], total: 1 }),
    );
    expect(container.querySelector('details')).not.toHaveAttribute('open');
    expect(container.querySelector('pre')).not.toBeVisible();
  });
  it('does not label partial execution complete or expose protocol JSON as the primary result', () => {
    const { container } = render(
      <SkillTrialResult
        result={{ status: 'PARTIAL_SUCCESS', data: { completedSteps: 1, errors: ['blocked'] } }}
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('Some steps completed');
    expect(container.querySelector('pre')).not.toBeVisible();
  });
});
