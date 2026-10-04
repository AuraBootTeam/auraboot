import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchResult } from '~/shared/services/http-client';
import { FieldHistoryViewer } from '../FieldHistoryViewer';

vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
const request = vi.mocked(fetchResult);
const pid = '01JLOTEXPIRYPUBLICPID';

beforeEach(() => request.mockReset());
describe('field history API contract and business feedback', () => {
  it('queries the public PID and displays the actual audited old/new dates', async () => {
    request.mockResolvedValue({ code: '0', desc: 'OK', data: [{
      id: 1, fieldCode: 'inv_lot_expiry_date', fieldLabel: '有效期至', valueType: 'date',
      oldValue: '2027-09-01', newValue: '2028-09-01', changeType: 'modified',
      commandCode: 'inv:update_lot', actorName: '仓库管理员', changedAt: '2026-10-03T09:00:00Z',
      changeReason: null,
    }] });
    render(<FieldHistoryViewer modelCode="inv_lot" recordPid={pid} />);
    const history = await screen.findByTestId('field-history');
    expect(request).toHaveBeenCalledWith('/api/audit/field-changes', expect.objectContaining({
      params: { modelCode: 'inv_lot', recordPid: pid },
    }));
    expect(history).toHaveTextContent('有效期至');
    expect(history).toHaveTextContent('2027-09-01');
    expect(history).toHaveTextContent('2028-09-01');
    expect(history).toHaveTextContent('修改');
    expect(history).toHaveTextContent('仓库管理员');
    expect(history).not.toHaveTextContent('inv:update_lot');
  });
  it('does not present a business failure as empty audit history', async () => {
    request.mockResolvedValue({ code: '35000', desc: 'Business error', data: null });
    render(<FieldHistoryViewer modelCode="inv_lot" recordPid={pid} />);
    expect(await screen.findByTestId('field-history-error')).toHaveTextContent('加载变更历史失败');
    expect(screen.queryByTestId('field-history-empty')).not.toBeInTheDocument();
  });
  it('distinguishes a successful empty history from failure', async () => {
    request.mockResolvedValue({ code: '0', desc: 'OK', data: [] });
    render(<FieldHistoryViewer modelCode="inv_lot" recordPid={pid} locale="en-US" />);
    expect(await screen.findByTestId('field-history-empty')).toHaveTextContent('No change history');
    expect(screen.queryByTestId('field-history-error')).not.toBeInTheDocument();
  });
});
