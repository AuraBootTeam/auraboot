import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { VersionHistoryPanel } from '../VersionHistoryPanel';
import type { VersionEntry } from '../types';

const versionHistoryTranslations = {
  'version.history.title': '版本历史',
  'version.history.close': '关闭版本面板',
  'version.history.empty.title': '暂无版本记录',
  'version.history.empty.description': '保存后会生成第一个版本',
  'version.history.loading': '正在加载版本...',
  'version.history.footer.empty': '暂无可用版本',
};

function renderPanel(versions: VersionEntry[] = []) {
  render(
    <I18nProvider initialLocale="zh-CN" initialData={versionHistoryTranslations}>
      <VersionHistoryPanel
        isOpen
        onClose={vi.fn()}
        versions={versions}
        isLoading={false}
        viewingVersionPid={null}
        onPreview={vi.fn()}
        onExitPreview={vi.fn()}
        onRollback={vi.fn()}
        isRollingBack={false}
      />
    </I18nProvider>,
  );
}

describe('VersionHistoryPanel', () => {
  it('uses localized empty-state copy instead of English fallback', () => {
    renderPanel();

    expect(screen.getByText('版本历史')).toBeInTheDocument();
    expect(screen.getByText('暂无版本记录')).toBeInTheDocument();
    expect(screen.getByText('保存后会生成第一个版本')).toBeInTheDocument();
    expect(screen.getByText('暂无可用版本')).toBeInTheDocument();
    expect(screen.queryByText('Version History')).not.toBeInTheDocument();
    expect(screen.queryByText('No versions yet')).not.toBeInTheDocument();
    expect(screen.queryByText('Save to create the first version')).not.toBeInTheDocument();
  });

  it('shows resolved actor names and never substitutes internal identifiers', () => {
    const base = {
      resourceType: 'report',
      resourceId: 'report',
      operation: 'update',
      operationAt: '2026-09-12T00:00:00Z',
    };
    renderPanel([
      {
        ...base,
        pid: 'v1',
        version: '1',
        operationBy: 'INTERNAL_ACTOR_A',
        operationByDisplayName: '张敏',
      },
      { ...base, pid: 'v2', version: '2', operationBy: 'INTERNAL_ACTOR_B' },
    ]);
    expect(screen.getByText(/张敏/)).toBeInTheDocument();
    expect(screen.getByText(/操作人不可用/)).toBeInTheDocument();
    expect(screen.queryByText(/INTERNAL_ACTOR/)).not.toBeInTheDocument();
  });

  it('anchors the drawer below the app header so the title remains visible', () => {
    renderPanel();

    const panel = screen.getByTestId('version-history-panel');
    expect(panel).toHaveClass('top-14');
    expect(panel).toHaveClass('h-[calc(100vh-3.5rem)]');
  });
});
