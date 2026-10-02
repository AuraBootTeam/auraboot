import fs from 'node:fs';
import path from 'node:path';
import { DndContext } from '@dnd-kit/core';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { RowHeightSelector } from '~/framework/smart/components/view/RowHeightSelector';
import { DraggableColumnHeader } from '~/framework/meta/rendering/pages/list/DraggableColumnHeader';
import { ListTabs } from '~/framework/meta/rendering/pages/list/ListTabs';
import {
  getBlockLabel,
  getBlockTypeLabel,
} from '~/plugins/core-designer/components/unified-designer/canvas/CanvasHost';
import { InboxBadge } from '../inbox/InboxBadge';
vi.mock('~/shared/services/inboxService', () => ({ getUnreadCount: vi.fn(async () => 3) }));
const seed = JSON.parse(
  fs.readFileSync(
    path.resolve(process.cwd(), '../platform/src/main/resources/seed/i18n-base.json'),
    'utf8',
  ),
) as Array<Record<string, string>>;
function translations(locale: string) {
  return Object.fromEntries(seed.filter((r) => r[locale]).map((r) => [r.key, r[locale]]));
}
function view(children: React.ReactNode, locale = 'zh-CN') {
  return render(
    <I18nProvider initialData={translations(locale)} initialLocale={locale}>
      {children}
    </I18nProvider>,
  );
}
describe('Chinese and English chrome use bundled translation copy', () => {
  it('row height button has a Chinese accessible label', () => {
    view(<RowHeightSelector onChange={vi.fn()} />);
    expect(screen.getByTitle('行高')).toBeVisible();
  });
  it('row height button follows English locale', () => {
    view(<RowHeightSelector onChange={vi.fn()} />, 'en-US');
    expect(screen.getByTitle('Row height')).toBeVisible();
  });
  it('inbox unread count interpolates the real count', async () => {
    view(<InboxBadge />);
    await waitFor(() => expect(screen.getByTitle('3 条未读')).toBeVisible());
  });
  it('column drag control translates its accessible name', () => {
    view(
      <DndContext>
        <table>
          <thead>
            <tr>
              <DraggableColumnHeader
                column={{ field: 'name' }}
                label="名称"
                sortable={false}
                onSort={vi.fn()}
                onResize={vi.fn()}
                onContextMenu={vi.fn()}
              />
            </tr>
          </thead>
        </table>
      </DndContext>,
    );
    expect(screen.getByRole('button', { name: '拖动调整列顺序' })).toBeVisible();
  });
  it('status navigation has a localized accessible label', () => {
    const words = translations('zh-CN');
    view(
      <ListTabs
        tabs={[{ key: 'all', label: '全部' }]}
        activeTab="all"
        onTabChange={vi.fn()}
        locale="zh-CN"
        t={(key) => words[key] || key}
      />,
    );
    expect(screen.getByRole('navigation', { name: '状态筛选' })).toBeVisible();
  });
  it('designer blocks use registry labels in Chinese and English', () => {
    expect(getBlockLabel({ id: 'table', blockType: 'table' }, 'zh-CN')).toBe('表格');
    expect(getBlockLabel({ id: 'table', blockType: 'table' }, 'en-US')).toBe('Table');
    expect(getBlockTypeLabel('action-bar', 'zh-CN')).toBe('操作栏');
  });
  it('designer button title resolves its actual translation key', () => {
    const words = translations('zh-CN');
    expect(
      getBlockLabel(
        { id: 'new', blockType: 'action', title: '$i18n:common.button.create' },
        'zh-CN',
        (key) => words[key] || key,
      ),
    ).toBe('新建');
  });
});
