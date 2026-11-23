import { createRef } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { renderDetailPageView } from '../renderDetailPageView';
vi.mock('~/framework/meta/rendering/components/PrintButton', () => ({
  PrintButton: () => <button>Print</button>,
}));
vi.mock('~/framework/smart/components/data-tools/ExportPdfButton', () => ({
  ExportPdfButton: () => <button>Export PDF</button>,
}));
vi.mock('~/framework/meta/rendering/components/AsyncTaskModalContext', () => ({
  AsyncTaskModalHost: () => null,
}));
vi.mock('~/framework/meta/runtime/actions/FormDialog', () => ({ default: () => null }));
function args(presentation: object | null) {
  return {
    presentation,
    recordLoading: false,
    recordError: null,
    recordAccessDenied: false,
    recordNotFound: false,
    schema: {
      modelCode: 'case',
      title: 'Case details',
      extension: {
        presentation: 'workspace',
        headerTitleField: 'subject',
        workspaceShell: { headerHeight: 64, mobileHeaderHeight: 55 },
        showBack: false,
        showShare: false,
        showReport: false,
        showInlineApprovalPanel: false,
        detailBodyOrder: 'schema',
      },
      blocks: [],
    },
    tableName: 'case',
    recordData: { subject: 'Actual event subject' },
    locale: 'en',
    t: (key: string) => key,
    headerLayout: { container: '', actions: '' },
    location: { search: '', key: 'default' },
    pdfExportTargetRef: createRef(),
    effectiveHeaderToolbar: { buttons: [] },
    canManageRecordShares: false,
    shareDialogOpen: false,
    tabs: [],
    asideBlockIds: new Set(),
    workspaceContentBlocks: [],
    persistentHeaderBlocks: [],
    renderWorkspaceBlock: vi.fn(),
    topLevelDetailBlocks: [],
    directMiscBlocks: [],
    effectiveDirectFormBlocks: [],
    directSubTableBlocks: [],
    directMonthlyGridBlocks: [],
    directEmbeddedListBlocks: [],
    directSpecializedBlocks: [],
    headerMiscBlocks: [],
    DETAIL_SPECIALIZED_BLOCK_TYPES: new Set(),
  } as unknown as Parameters<typeof renderDetailPageView>[0];
}
describe('actual detail presentation body', () => {
  it('leaves the sole event title to the modal host while retaining utilities and a zero minimum height', () => {
    render(
      <MemoryRouter>
        {renderDetailPageView(args({ mode: 'modal', footerTarget: null, onClose: vi.fn() }))}
      </MemoryRouter>,
    );
    expect(screen.queryByRole('heading', { name: 'Actual event subject' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Print' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Export PDF' })).toBeInTheDocument();
    expect(screen.getByTestId('ab:detail:case:container')).toHaveStyle({ minHeight: 0 });
  });
  it('keeps the normal detail heading and reads the same actual-height variable for its minimum height', () => {
    render(<MemoryRouter>{renderDetailPageView(args(null))}</MemoryRouter>);
    expect(screen.getByRole('heading', { name: 'Actual event subject' })).toBeInTheDocument();
    expect(screen.getByTestId('ab:detail:case:container')).toHaveStyle({
      minHeight: 'calc(100vh - var(--workspace-header-height, 64px))',
    });
  });
});

describe('detail permission recovery', () => {
  it.each([
    ['en', 'Go to home'],
    ['zh-CN', '返回首页'],
  ])('returns denied deep links to an accessible home in %s', (locale, label) => {
    render(
      <MemoryRouter>
        {renderDetailPageView({
          ...args(null),
          locale,
          recordError: 'Forbidden',
          recordAccessDenied: true,
        })}
      </MemoryRouter>,
    );
    expect(screen.getByRole('link', { name: label })).toHaveAttribute('href', '/home');
    expect(screen.queryByText('Actual event subject')).not.toBeInTheDocument();
  });

  it('keeps non-permission errors on the original model list', () => {
    render(
      <MemoryRouter>
        {renderDetailPageView({ ...args(null), recordError: 'Not found', recordNotFound: true })}
      </MemoryRouter>,
    );
    expect(screen.getByRole('link')).toHaveAttribute('href', '/p/case');
  });

  it('closes a denied modal without navigating its host', () => {
    const onClose = vi.fn();
    render(
      <MemoryRouter>
        {renderDetailPageView({
          ...args({ mode: 'modal', footerTarget: null, onClose }),
          closeLabel: 'Close',
          recordError: 'Forbidden',
          recordAccessDenied: true,
        })}
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledOnce();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });
});

describe('detail header business status', () => {
  it('does not display header values retained for a different record', () => {
    render(<MemoryRouter>{renderDetailPageView({
      ...args(null), recordData: { pid: 'current', subject: 'Current record' },
      runtime: { getContext: () => ({ form: { pid: 'previous', subject: 'Previous record' } }) } as any,
    } as Parameters<typeof renderDetailPageView>[0])}</MemoryRouter>);
    expect(screen.getByRole('heading', { name: 'Current record', exact: true })).toBeInTheDocument();
    expect(screen.queryByText('Previous record')).not.toBeInTheDocument();
  });

  it('reflects refreshed form header facts without replacing the persisted record or drafts', () => {
    const base = args(null);
    const persisted = { subject: 'Original title', progress: 'ready', deadline_display: 'Original cutoff' };
    let form: Record<string, unknown> = {};
    const renderView = () => <MemoryRouter>{renderDetailPageView({
      ...base,
      schema: { ...base.schema, extension: { ...base.schema.extension,
        headerStatusField: 'progress', headerStatusDictCode: 'business_status', headerSubtitleFields: ['deadline'],
      } },
      recordData: persisted,
      runtime: { getContext: () => ({ form }) } as any,
      enrichField: (field: any) => field,
      getDictItems: () => [{ value: 'ready', label: 'All replied' }, { value: 'collecting', label: 'Awaiting reply' }],
    } as Parameters<typeof renderDetailPageView>[0])}</MemoryRouter>;
    const view = render(renderView());
    expect(screen.getByText('All replied')).toBeInTheDocument();
    form = { subject: 'Updated title', progress: 'collecting', deadline_display: 'Updated cutoff', rows: [{ quantity: '99' }] };
    view.rerender(renderView());
    expect(screen.getByRole('heading', { name: 'Updated title', exact: true })).toBeInTheDocument();
    expect(screen.getByText('Awaiting reply')).toBeInTheDocument();
    expect(screen.getByText('Updated cutoff')).toBeInTheDocument();
    expect(persisted.progress).toBe('ready');
    expect(form.rows).toEqual([{ quantity: '99' }]);
  });

  it.each([['business_status', 'business_status'], [undefined, 'native_status']])(
    'uses the configured header dictionary %s while retaining the field default', (override, expected) => {
      const base = args(null);
      const getDictItems = vi.fn(() => [{ value: 'ready', label: 'All suppliers replied' }]);
      render(<MemoryRouter>{renderDetailPageView({
        ...base,
        schema: { ...base.schema, extension: { ...base.schema.extension, headerStatusField: 'progress', headerStatusDictCode: override } },
        recordData: { subject: 'Event', progress: 'ready' },
        enrichField: (field: any) => ({ ...field, dictCode: 'native_status' }),
        getDictItems,
      } as Parameters<typeof renderDetailPageView>[0])}</MemoryRouter>);
      expect(getDictItems).toHaveBeenCalledWith(expected);
      expect(screen.getByText('All suppliers replied')).toBeInTheDocument();
    },
  );

  it.each([
    ['blue', 'blue'], ['green', 'green'], ['gray', 'gray'],
    ['amber', 'amber'], ['red', 'red'], ['#10b981', 'green'], ['unsupported', 'gray'],
  ])('uses the declared dictionary color %s for the header status', (color, tone) => {
    const base = args(null);
    render(<MemoryRouter>{renderDetailPageView({
      ...base,
      schema: { ...base.schema, extension: { ...base.schema.extension,
        headerStatusField: 'progress', headerStatusDictCode: 'business_status',
      } },
      recordData: { subject: 'Event', progress: 'ready' },
      enrichField: (field: any) => field,
      getDictItems: () => [{ value: 'ready', label: 'All replied', extension: { color } }],
    } as Parameters<typeof renderDetailPageView>[0])}</MemoryRouter>);
    const status = screen.getByTestId('detail-header-status');
    expect(status).toHaveTextContent('All replied');
    expect(status).toHaveClass(`bg-status-${tone}-bg`, `text-status-${tone}`);
  });

  it('retains the existing accent appearance when the dictionary declares no color', () => {
    const base = args(null);
    render(<MemoryRouter>{renderDetailPageView({
      ...base,
      schema: { ...base.schema, extension: { ...base.schema.extension,
        headerStatusField: 'progress', headerStatusDictCode: 'business_status',
      } },
      recordData: { subject: 'Event', progress: 'ready' },
      enrichField: (field: any) => field,
      getDictItems: () => [{ value: 'ready', label: 'All replied' }],
    } as Parameters<typeof renderDetailPageView>[0])}</MemoryRouter>);
    expect(screen.getByTestId('detail-header-status')).toHaveClass('text-accent', 'bg-accent-weak');
  });
});
