import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { WorkspaceRecordSection } from '../WorkspaceRecordSection';
const request = vi.fn();
vi.mock('~/shared/services/http-client', () => ({ fetchResult: (...args: unknown[]) => request(...args) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showErrorToast: vi.fn() }) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en' }) }));
vi.mock('react-router', () => ({ Link: ({ to, children }: any) => <a href={to}>{children}</a> }));
describe('Referenced business facts', () => {
  it('loads the declared attribute using canonical targetEntity metadata and the actual public PID', async () => {
    request.mockResolvedValue({ code: '0', desc: 'OK', data: { customer_contact: 'Taylor · Procurement' } });
    render(<WorkspaceRecordSection block={{ id: 'facts', title: 'Information' }} fields={[{ field: 'customer', label: 'Contact', dataType: 'reference', refTarget: { targetEntity: 'customers' }, referenceDisplayField: 'customer_contact' }]} record={{ customer: 'customer-1' }} locale="en" t={(key: string) => key} getDictItems={() => []} />);
    expect(await screen.findByText('Taylor · Procurement')).toBeInTheDocument();
    expect(request).toHaveBeenCalledWith('/api/dynamic/customers/customer-1', expect.objectContaining({ method: 'get' }));
    expect(screen.queryByText('customer-1')).not.toBeInTheDocument();
  });
  it('shows a failed reference lookup explicitly rather than fabricating a contact', async () => {
    request.mockResolvedValue({ code: '403', desc: 'Denied', data: null });
    render(<WorkspaceRecordSection block={{ id: 'facts', title: 'Information' }} fields={[{ field: 'customer', label: 'Contact', dataType: 'reference', refTarget: { targetEntity: 'customers' }, referenceDisplayField: 'customer_contact' }]} record={{ customer: 'customer-2' }} locale="en" t={(key: string) => key} getDictItems={() => []} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the related object');
  });
});
