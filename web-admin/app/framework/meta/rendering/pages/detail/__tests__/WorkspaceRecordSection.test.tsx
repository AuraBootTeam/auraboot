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
  it('renders submitted rich text as sanitized content instead of exposing markup', () => {
    const { container } = render(<WorkspaceRecordSection block={{ id: 'snapshot', title: 'Submission' }} fields={[{ field: 'content', label: 'Content', component: 'richtext', colSpan: 12 }]} record={{ content: '<p><strong>Frozen policy</strong></p><script>alert(1)</script><img src=x onerror="alert(1)">' }} locale="en" t={(key: string) => key} getDictItems={() => []} />);
    expect(screen.getByTestId('readonly-richtext-content').querySelector('strong')).toHaveTextContent('Frozen policy');
    expect(container.querySelector('script')).toBeNull();expect(container.querySelector('[onerror]')).toBeNull();
    expect(screen.queryByText(/<p>/)).not.toBeInTheDocument();
  });
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
  it('presents boolean facts in business language', () => {
    render(<WorkspaceRecordSection block={{id:'facts',title:'Facts'}} fields={[{field:'complete',label:'Complete',dataType:'boolean'},{field:'registered',label:'Registered',dataType:'boolean'}]} record={{complete:true,registered:false}} locale="zh-CN" t={(key:string)=>key} getDictItems={()=>[]} />);
    expect(screen.getByText('是')).toBeInTheDocument();expect(screen.getByText('否')).toBeInTheDocument();
    expect(screen.queryByText('true')).not.toBeInTheDocument();
  });
  it('formats a timestamp in the declared workspace timezone and preserves currency cents', () => {
    render(<WorkspaceRecordSection block={{ id: 'snapshot', title: 'Snapshot' }} fields={[
      { field: 'checked_at', label: 'Checked at', dataType: 'datetime' },
      { field: 'budget', label: 'Available', dataType: 'decimal', presentation: { kind: 'currency', currencyCode: 'CNY', precision: 2 } },
    ]} record={{ checked_at: '2026-10-16T14:42:39.804+00:00', budget: '68000.01' }} locale="zh-CN" timezone="Asia/Shanghai" t={(key: string) => key} getDictItems={() => []} />);
    expect(screen.getByText('2026年10月16日 22:42:39')).toBeInTheDocument();
    expect(screen.getByText(/68,000\.01/)).toBeInTheDocument();
    expect(screen.queryByText(/T14:42/)).not.toBeInTheDocument();
  });

});
