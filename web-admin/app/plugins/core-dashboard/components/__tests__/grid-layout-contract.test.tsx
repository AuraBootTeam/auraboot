import type { ReactNode } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { DashboardViewer } from '../DashboardViewer';
import { DesignerCanvas } from '../DesignerCanvas';
import type { Widget } from '../../types';

const actions = vi.hoisted(() => ({ select: vi.fn(), update: vi.fn(), add: vi.fn() }));
const widgets: Widget[] = [
  {
    id: 'owned-widget',
    type: 'smart-number-card',
    componentType: 'smart-number-card',
    props: {},
    x: 0,
    y: 0,
    w: 12,
    h: 2,
    config: { title: 'Production' },
  },
];
vi.mock('../WidgetRenderer', () => ({
  renderWidget: ({ autoHeight }: { autoHeight?: boolean }) => (
    <div data-auto-height={String(Boolean(autoHeight))}>Actual widget boundary</div>
  ),
}));
vi.mock('~/framework/smart/components/dashboard/ChartWidgetWrapper', () => ({
  ChartWidgetWrapper: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));
vi.mock('~/framework/smart/components/data-tools/ExportPdfButton', () => ({
  ExportPdfButton: () => null,
}));
vi.mock('../DashboardExportExcel', () => ({ DashboardExportExcel: () => null }));
vi.mock('../../store/useDashboardStore', () => ({
  useDashboardStore: () => ({
    widgets,
    layoutConfig: { columns: 12, rowHeight: 80, gap: 16 },
    selectedWidgetId: null,
    selectWidget: actions.select,
    updateLayout: actions.update,
    addWidget: actions.add,
  }),
}));

it('the actual installed grid respects saved row height and makes the viewer read-only', () => {
  const { container } = render(
    <DashboardViewer widgets={widgets} layoutConfig={{ columns: 12, rowHeight: 80, gap: 16 }} />,
  );
  const item = screen.getByTestId('dashboard-block-owned-widget');
  expect(item.style.height).toBe('176px');
  expect(item).not.toHaveClass('react-draggable');
  expect(container.querySelector('.react-resizable-handle')).toBeNull();
});

it('the actual designer grid uses the same saved geometry and retains editing controls', () => {
  const { container } = render(<DesignerCanvas />);
  const item = container.querySelector('.react-grid-item') as HTMLElement | null;
  expect(item?.style.height).toBe('176px');
  expect(item).toHaveClass('react-draggable');
  expect(container.querySelector('.react-resizable-handle')).not.toBeNull();
  fireEvent.click(screen.getByText('Actual widget boundary'));
  expect(actions.select).toHaveBeenCalledWith('owned-widget');
});

it('narrow viewers flow metric content in reading order without a fixed-height clipping grid', () => {
  let resize!: ResizeObserverCallback;
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback: ResizeObserverCallback) {
        resize = callback;
      }
      observe() {}
      disconnect() {}
    },
  );
  try {
    const chart: Widget = { ...widgets[0], id: 'chart', type: 'smart-bar-chart', y: 2, h: 3 };
    const { container } = render(
      <DashboardViewer
        widgets={[chart, widgets[0]]}
        layoutConfig={{ columns: 12, rowHeight: 80, gap: 16 }}
      />,
    );
    act(() =>
      resize([{ contentRect: { width: 422 } } as ResizeObserverEntry], {} as ResizeObserver),
    );
    const metric = screen.getByTestId('dashboard-block-owned-widget');
    const chartItem = screen.getByTestId('dashboard-block-chart');
    expect(container.querySelector('.react-grid-layout')).toBeNull();
    expect(metric.style.height).toBe('');
    expect(metric.style.minHeight).toBe('176px');
    expect(metric.querySelector('[data-auto-height="true"]')).not.toBeNull();
    expect(chartItem.style.height).toBe('272px');
    expect(Array.from(metric.parentElement!.children)).toEqual([metric, chartItem]);
    act(() =>
      resize([{ contentRect: { width: 1232 } } as ResizeObserverEntry], {} as ResizeObserver),
    );
    expect(container.querySelector('.react-grid-layout')).not.toBeNull();
    expect(screen.getByTestId('dashboard-block-owned-widget').style.height).toBe('176px');
  } finally {
    vi.unstubAllGlobals();
  }
});
