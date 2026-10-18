import { useContext, type ReactNode } from 'react';
import { render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => vi.unstubAllGlobals());
import { DashboardQueryContext } from '~/framework/smart/hooks/DashboardQueryContext';
import type { Widget } from '../../types';
import { DashboardViewer } from '../DashboardViewer';

vi.mock('react-grid-layout/legacy', () => ({
  default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));
vi.mock('../WidgetRenderer', () => ({ renderWidget: () => <Probe /> }));
vi.mock('~/framework/smart/components/dashboard/ChartWidgetWrapper', () => ({
  ChartWidgetWrapper: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));
vi.mock('~/framework/smart/components/data-tools/ExportPdfButton', () => ({
  ExportPdfButton: () => null,
}));
vi.mock('../DashboardExportExcel', () => ({ DashboardExportExcel: () => null }));

function Probe() {
  const binding = useContext(DashboardQueryContext);
  return (
    <div data-testid="usage" data-visit={binding?.usageId} data-dashboard={binding?.dashboardPid} />
  );
}
const widgets: Widget[] = [
  {
    id: 'widget',
    type: 'smart-table-chart',
    componentType: 'smart-table-chart',
    props: {},
    x: 0,
    y: 0,
    w: 6,
    h: 4,
    config: { title: 'Orders' },
  },
];
it.each(['native UUID', 'HTTP origin without randomUUID'])('keeps valid UUID visits stable across renders and distinct across dashboard switches (%s)', (environment) => {
  if (environment === 'HTTP origin without randomUUID') {
    vi.stubGlobal('crypto', { getRandomValues: crypto.getRandomValues.bind(crypto) });
  }
  const view = (dashboardPid: string) => (
    <DashboardViewer
      dashboardPid={dashboardPid}
      widgets={widgets}
      layoutConfig={{ columns: 12, rowHeight: 80, gap: 8 }}
    />
  );
  const { rerender } = render(view('first'));
  const original = screen.getByTestId('usage').getAttribute('data-visit');
  expect(original).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  rerender(view('first'));
  expect(screen.getByTestId('usage')).toHaveAttribute('data-visit', original!);
  rerender(view('second'));
  const second = screen.getByTestId('usage').getAttribute('data-visit');
  expect(second).not.toBe(original);
  expect(screen.getByTestId('usage')).toHaveAttribute('data-dashboard', 'second');
  rerender(view('first'));
  const returned = screen.getByTestId('usage').getAttribute('data-visit');
  expect(returned).not.toBe(original);
  expect(returned).not.toBe(second);
});
