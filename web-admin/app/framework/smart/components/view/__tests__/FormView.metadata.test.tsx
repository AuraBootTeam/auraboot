import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ fetch: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: mocks.fetch }));
vi.mock('~/framework/meta/rendering/ControlledFieldRenderer', () => ({
  ControlledFieldRenderer: ({ field }: any) => <input aria-label={field.label} />,
}));
import { FormView } from '../FormView';
describe('FormView live metadata contract', () => {
  beforeEach(() => mocks.fetch.mockReset());
  it('renders configured model fields from the dynamic field-meta array endpoint', async () => {
    mocks.fetch.mockResolvedValue({
      code: '0',
      data: [
        { code: 'subject', displayName: 'Delivery item', dataType: 'string', required: true },
        { code: 'created_at', displayName: 'Created', dataType: 'datetime' },
      ],
    });
    render(<FormView modelCode="delivery_line" viewConfig={{ formFields: ['subject'] }} />);
    expect(await screen.findByRole('textbox', { name: 'Delivery item' })).toBeVisible();
    expect(mocks.fetch).toHaveBeenCalledWith('/api/dynamic/delivery_line/field-meta');
    expect(screen.queryByRole('textbox', { name: 'Created' })).not.toBeInTheDocument();
  });
  it('surfaces a rejected metadata response instead of pretending the model has no fields', async () => {
    mocks.fetch.mockResolvedValue({ code: '403', message: 'Access denied', data: null });
    render(<FormView modelCode="delivery_line" />);
    expect(await screen.findByText('Access denied')).toBeVisible();
  });
});
