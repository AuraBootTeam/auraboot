import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import { ListErrorState, type ListErrorStateProps } from '../ListErrorState';

const back: ButtonConfig = {
  code: 'back_rfqs', label: { 'zh-CN': '返回询价列表', en: 'Back to RFQs' },
  permissionCode: 'qo.procurement.manage', action: { type: 'navigate', to: '/p/c/qo_pr_rfq_list' },
};
function props(overrides: Partial<ListErrorStateProps> = {}): ListErrorStateProps {
  return { title: '当前报价明细', error: '分页参数无效，请核对页码和每页条数。',
    errorTitle: '加载失败', retryLabel: '重试', buttons: [back],
    evaluateVisible: vi.fn(() => true), resolveLabel: () => '返回询价列表',
    onNavigate: vi.fn(), onRetry: vi.fn(), ...overrides };
}

describe('ListErrorState', () => {
  it('retains the localized object title beside the human load error', () => {
    render(<ListErrorState {...props()} />);
    expect(screen.getByRole('heading', { name: '当前报价明细' })).toBeInTheDocument();
    expect(screen.getByText('分页参数无效，请核对页码和每页条数。')).toBeInTheDocument();
  });

  it('uses the authorized static DSL destination without a business action handler', () => {
    const options = props(); render(<ListErrorState {...options} />);
    fireEvent.click(screen.getByRole('button', { name: '返回询价列表' }));
    expect(options.evaluateVisible).toHaveBeenCalledWith(back);
    expect(options.onNavigate).toHaveBeenCalledExactlyOnceWith('/p/c/qo_pr_rfq_list');
  });

  it('hides navigation denied by existing permission/visibility evaluation', () => {
    const options = props({ evaluateVisible: vi.fn(() => false) });
    render(<ListErrorState {...options} />);
    expect(screen.queryByRole('button', { name: '返回询价列表' })).not.toBeInTheDocument();
    expect(options.onNavigate).not.toHaveBeenCalled();
  });

  it('rechecks permission at activation if the session gate changes after rendering', () => {
    const gate = vi.fn().mockReturnValueOnce(true).mockReturnValue(false);
    const options = props({ evaluateVisible: gate }); render(<ListErrorState {...options} />);
    fireEvent.click(screen.getByRole('button', { name: '返回询价列表' }));
    expect(options.onNavigate).not.toHaveBeenCalled();
  });

  it('never exposes a command-bearing navigate or other write/flow side effects', () => {
    const command = vi.fn();
    const unsafe = [
      { ...back, action: { type: 'navigate', to: '/p/c/new', command: 'internal:create' } },
      { ...back, action: { type: 'navigate', to: '/p/c/new', params: { source: 'value' } } },
      { ...back, action: { type: 'command', command: 'internal:create' } },
      { ...back, handler: command }, { ...back, commandCode: 'internal:delete' },
      { ...back, events: { click: command } }, { ...back, apiAction: { endpoint: '/api/write' } },
      { ...back, action: { type: 'navigate', to: '/p/c/list', hardReload: true } },
      { ...back, preset: 'create' }, { ...back, reportCode: 'write-report' },
    ] as unknown as ButtonConfig[];
    const options = props({ buttons: unsafe }); render(<ListErrorState {...options} />);
    expect(screen.queryByRole('button', { name: '返回询价列表' })).not.toBeInTheDocument();
    expect(command).not.toHaveBeenCalled(); expect(options.onNavigate).not.toHaveBeenCalled();
  });

  it('rejects external, encoded, dynamic, and API destinations', () => {
    const destinations = ['https://example.invalid', '//example.invalid', '/\\example.invalid',
      '/p/c/{pid}', '/p/c/${state.target}', '/p/c/%7Bpid%7D', '/%2fexample.invalid',
      '/p/c/list?record=%24%7Brecord.pid%7D', '/api/meta/commands/execute/test', '/p/c/list\n'];
    const options = props({ buttons: destinations.map(to => ({ ...back, action: { type: 'navigate', to } })) });
    render(<ListErrorState {...options} />);
    expect(screen.queryByRole('button', { name: '返回询价列表' })).not.toBeInTheDocument();
    expect(options.onNavigate).not.toHaveBeenCalled();
  });

  it('does not activate disabled or dynamic enablement navigation on failed data', () => {
    const options = props({ buttons: [{ ...back, disabled: true }, { ...back, enableWhen: 'recordCount > 0' },
      { ...back, disableWhen: 'state.busy' }] });
    render(<ListErrorState {...options} />);
    expect(screen.queryByRole('button', { name: '返回询价列表' })).not.toBeInTheDocument();
    expect(options.onNavigate).not.toHaveBeenCalled();
  });

  it('keeps retry callable with its localized label and no view-management controls', () => {
    const options = props(); render(<ListErrorState {...options} />);
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(options.onRetry).toHaveBeenCalledOnce();
    expect(screen.queryByRole('button', { name: /新建视图|管理视图|导入|导出/ })).not.toBeInTheDocument();
    expect(options.onNavigate).not.toHaveBeenCalled();
  });
});
