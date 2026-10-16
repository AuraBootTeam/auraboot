import React from 'react';
import {render, screen, fireEvent, waitFor} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';
import {RelationField} from '../RelationField';
const st = (value: any) => value.zh;
vi.mock('~/utils/i18n', () => ({useSmartText: () => st}));
const props = {name: 'target', label: '目标任务', targetModelCode: 'demo_task', displayField: 'label', valueField: 'value', relationType: 'many_to_one' as const};
describe('Relation picker error visibility', () => {
  it('shows read errors inside the open dropdown instead of a misleading empty result', async () => {
    render(<RelationField {...props} error="无权读取此空间的任务" fetchOptions={vi.fn().mockResolvedValue([])} />);
    fireEvent.click(document.getElementById('target')!);
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('无权读取此空间的任务'));
    expect(screen.queryByText('暂无可选记录')).not.toBeInTheDocument();
    fireEvent.click(document.getElementById('target')!);
    expect(screen.getByRole('alert')).toHaveTextContent('无权读取此空间的任务');
  });
  it('keeps genuine empty search results distinct from errors', async () => {
    render(<RelationField {...props} fetchOptions={vi.fn().mockResolvedValue([])} />);
    fireEvent.click(document.getElementById('target')!);
    await waitFor(() => expect(screen.getByText('暂无可选记录')).toBeVisible());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
