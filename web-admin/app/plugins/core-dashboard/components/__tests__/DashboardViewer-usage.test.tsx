import { useContext, type ReactNode } from 'react';
import { render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
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
afterEach(() => vi.unstubAllGlobals());
it('keeps one visit while rendering and starts a new visit on each dashboard switch', () => {
  const view = (dashboardPid: string) => (
    <DashboardViewer
      dashboardPid={dashboardPid}
      widgets={widgets}
      layoutConfig={{ columns: 12, rowHeight: 80, gap: 8 }}
    />
  );
  const { rerender } = render(view('first'));
  const original = screen.getByTestId('usage').getAttribute('data-visit');
  expect(original).toMatch(/^[0-9a-f-]{36}$/);
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

it('renders a valid UUID visit without the secure-context randomUUID method', () => {
  const getRandomValues = globalThis.crypto.getRandomValues.bind(globalThis.crypto);
  vi.stubGlobal('crypto', { getRandomValues });
  render(<DashboardViewer dashboardPid="http-origin" widgets={widgets} layoutConfig={{ columns: 12, rowHeight: 80, gap: 8 }} />);
  expect(screen.getByTestId('usage').getAttribute('data-visit')).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );
});
