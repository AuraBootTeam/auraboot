import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { WorkspaceCell, calendarDayDifference } from '../WorkspaceCells';

const base = { column: { field: 'name', presentation: { kind: 'object' as const, subtitleFields: ['code', 'customer'] } }, locale: 'zh-CN' };
describe('Workspace business cells', () => {
  it('shows a business title with code and customer rather than the public storage identity', () => {
    render(<WorkspaceCell {...base} value="供应链协同平台" record={{ code: 'PRJ-2026-021', customer: '华辰制造' }} />);
    expect(screen.getByText('供应链协同平台')).toBeVisible();
    expect(screen.getByText('PRJ-2026-021 · 华辰制造')).toBeVisible();
  });
  it('keeps storage identities out of secondary business text and unresolved members', () => {
    const pid = '01M4WZNGQVFPQG40DWVS0F8YES';
    const { container } = render(<><WorkspaceCell {...base} value="Project" record={{ code: pid }} /><WorkspaceCell value={pid} record={{}} column={{ field: 'owner', presentation: { kind: 'member' } }} /></>);
    expect(container).not.toHaveTextContent(pid);
  });
  it('uses the resolved member name and a text initial', () => {
    render(<WorkspaceCell value="01M4WZNGQVFPQG40DWVS0F8YES" record={{ owner_display: '林晓' }} column={{ field: 'owner', presentation: { kind: 'member' } }} />);
    expect(screen.getByText('林晓')).toBeVisible();
    expect(screen.getByText('林')).toBeVisible();
  });
  it('exposes a semantic percentage independent from a milestone count', () => {
    render(<WorkspaceCell value={68} record={{ milestones: 2 }} column={{ field: 'progress', presentation: { kind: 'progress' } }} />);
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '68');
    expect(screen.getByText('68%')).toBeVisible();
  });
  it.each([-1, 101, null, 'invalid'])('does not invent a progress value for %s', value => {
    const { container } = render(<WorkspaceCell value={value} record={{}} column={{ field: 'progress', presentation: { kind: 'progress' } }} />);
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
  });
  it('computes calendar days in the tenant timezone across UTC midnight', () => {
    expect(calendarDayDifference('2026-10-28', new Date('2026-10-14T20:00:00Z'), 'Asia/Shanghai')).toBe(13);
    expect(calendarDayDifference('2026-10-28', new Date('2026-10-14T20:00:00Z'), 'UTC')).toBe(14);
  });
  it('does not report a completed delivery as currently overdue', () => {
    render(<WorkspaceCell value="2020-10-01" record={{ state: 'completed' }} locale="zh-CN" column={{ field: 'date', presentation: { kind: 'deadline', completedField: 'state', completedValues: ['completed'] } }} />);
    expect(screen.getByText('已交付')).toBeVisible();
    expect(screen.queryByText(/逾期/)).toBeNull();
  });
  it('formats other currencies through the same presentation contract', () => {
    render(<WorkspaceCell value={128000} record={{}} locale="en-US" column={{ field: 'amount', presentation: { kind: 'currency', currencyCode: 'USD', precision: 0 } }} />);
    expect(screen.getByText('$128,000')).toBeVisible();
  });
});
