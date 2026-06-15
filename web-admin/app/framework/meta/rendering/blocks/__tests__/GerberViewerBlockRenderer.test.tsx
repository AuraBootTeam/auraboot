import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { evaluateCondition as evaluateExpressionCondition } from '~/framework/meta/runtime/expression/evaluator';

import { GerberViewerBlockRenderer } from '../GerberViewerBlockRenderer';

const INSPECTION = {
  project: { code: 'A00104001', name: 'PCBA RFQ demo board' },
  board: { xMinMm: 0, yMinMm: 0, xMaxMm: 98, yMaxMm: 33, widthMm: 98, heightMm: 33 },
  summary: {
    bomRefCount: 3,
    cplRefCount: 3,
    smdCount: 2,
    thtCount: 1,
    excludedBomRefCount: 1,
    errorCount: 1,
    warningCount: 1,
  },
  layerManifest: [
    {
      filename: 'Gerber_TopLayer.GTL',
      role: 'top_copper',
      side: 'top',
      kind: 'gerber',
      flashCount: 258,
    },
    {
      filename: 'Gerber_BottomLayer.GBL',
      role: 'bottom_copper',
      side: 'bottom',
      kind: 'gerber',
      flashCount: 101,
    },
  ],
  drillFiles: [{ filename: 'Drill_PTH_Through.DRL', plated: true, hitCount: 101 }],
  issues: [
    {
      severity: 'error',
      code: 'outside_board',
      refdes: 'U1',
      message: 'U1 is outside the board outline.',
    },
    {
      severity: 'warning',
      code: 'bom_process_mismatch',
      refdes: 'J1',
      message: 'J1 process differs from CPL.',
    },
  ],
  excludedBomRefs: [{ refdes: 'TP1', bomItem: { materialCode: 'TP', materialName: 'test point' } }],
  components: [
    {
      refdes: 'C4',
      footprint: '0603',
      xMm: 12,
      yMm: 8,
      side: 'top',
      smd: true,
      pins: 2,
      rotation: 90,
      issues: [],
      bomItem: { materialName: 'Capacitor', process: 'SMT' },
    },
    {
      refdes: 'U1',
      footprint: 'QFN32',
      xMm: 92,
      yMm: 31,
      side: 'top',
      smd: true,
      pins: 32,
      rotation: 0,
      issues: [
        { severity: 'error', code: 'outside_board', refdes: 'U1', message: 'U1 is outside.' },
      ],
      bomItem: { materialName: 'MCU', process: 'SMT' },
    },
    {
      refdes: 'J1',
      footprint: 'HDR',
      xMm: 40,
      yMm: 15,
      side: 'bottom',
      smd: false,
      pins: 4,
      rotation: 180,
      issues: [
        {
          severity: 'warning',
          code: 'bom_process_mismatch',
          refdes: 'J1',
          message: 'J1 mismatch.',
        },
      ],
      bomItem: { materialName: 'Header', process: 'DIP' },
    },
  ],
};

function makeRuntime(overrides: Partial<any> = {}): SchemaRuntime {
  const context: Record<string, any> = {
    locale: 'en-US',
    t: (key: string) => key,
    form: {},
    global: {},
    state: {
      selectedLine: {
        qo_ql_description: 'Demo PCBA',
        qo_ql_gerber_parse_status: 'parsed',
        qo_ql_gerber_validation_status: 'warning',
        qo_ql_board_width_mm: 98,
        qo_ql_board_height_mm: 33,
      },
    },
  };
  const data = overrides.data ?? {};
  return {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: (expr: string, expressionContext = context) =>
        evaluateExpressionCondition(expr, expressionContext as any),
      evaluateTemplate: (tpl: string) => tpl,
      evaluateObject: (obj: any) => obj,
    }),
    getDataSourceManager: () => ({
      getData: (id: string) => data[id],
      getState: () => ({ data: null, loading: false, error: null }),
      has: (id: string) => Object.prototype.hasOwnProperty.call(data, id),
      register: vi.fn(),
      reload: vi.fn(),
    }),
    getStateManager: () => ({
      updateState: vi.fn(),
      getContext: () => context,
      getStore: () => ({ subscribe: () => () => undefined }),
    }),
    getScopeId: () => 'scope-1',
    getSchema: () => ({ id: 'test_schema', modelCode: 'test_model' }),
    ...overrides,
  } as unknown as SchemaRuntime;
}

function stubBoardImageFetch(objectUrl = 'blob:gerber-board-svg') {
  const createObjectURL = vi.fn().mockReturnValue(objectUrl);
  const revokeObjectURL = vi.fn();
  const blob = new Blob(['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10" />'], {
    type: 'image/svg+xml',
  });
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    blob: vi.fn().mockResolvedValue(blob),
  });

  vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
  vi.stubGlobal('fetch', fetchMock);

  return { createObjectURL, fetchMock, objectUrl, revokeObjectURL };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('GerberViewerBlockRenderer', () => {
  it('renders inline inspection metrics, board layers and selected line facts', () => {
    const runtime = makeRuntime();
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: INSPECTION,
      lineContext: '${state.selectedLine}',
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('gerber-viewer')).toHaveTextContent('A00104001');
    expect(screen.getByTestId('gerber-viewer-board')).toBeInTheDocument();
    expect(screen.getByTestId('gerber-viewer-board')).not.toHaveClass('bg-gray-950');
    expect(screen.getByTestId('gerber-svg-unavailable')).toHaveTextContent(
      'Gerber preview needs attention',
    );
    expect(screen.getByTestId('gerber-svg-unavailable')).toHaveTextContent(
      'No real board preview was generated for this side.',
    );
    expect(screen.getByTestId('gerber-svg-unavailable')).not.toHaveClass('bg-slate-950');
    expect(screen.queryByRole('img', { name: 'PCB layer render' })).toBeNull();
    expect(screen.getByTestId('gerber-metric-board')).toHaveTextContent('98 x 33 mm');
    expect(screen.getByTestId('gerber-metric-parse')).toHaveTextContent('parsed / warning');
    expect(screen.getByTestId('gerber-validation-summary')).toHaveTextContent(
      'Validation errors found',
    );
    expect(screen.getAllByRole('button', { name: 'All' })).toHaveLength(1);
    expect(screen.getByTestId('gerber-layer-top_copper')).toHaveTextContent('258');
    expect(screen.queryByTestId('gerber-marker-C4')).toBeNull();
    expect(screen.getByTestId('gerber-component-row-C4')).toBeInTheDocument();
  });

  it('shows upload guidance when no Gerber parse status or artifact exists', () => {
    const runtime = makeRuntime();
    const context = runtime.getContext() as any;
    context.state.selectedLine = {
      pid: 'LINE-NO-GERBER',
      qo_ql_description: 'Quote line without Gerber',
      qo_ql_board_width_mm: 100,
      qo_ql_board_height_mm: 60,
    };
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: {
        project: { code: 'NO-GERBER', name: 'Missing upload quote line' },
        board: { widthMm: 100, heightMm: 60 },
        summary: {},
        layerManifest: [],
        components: [],
      },
      lineContext: '${state.selectedLine}',
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('gerber-metric-parse')).toHaveTextContent('- / -');
    expect(screen.getByTestId('gerber-viewer-board')).not.toHaveClass('bg-gray-950');
    expect(screen.getByTestId('gerber-svg-unavailable')).toHaveTextContent(
      'No Gerber file uploaded',
    );
    expect(screen.getByTestId('gerber-svg-unavailable')).toHaveTextContent(
      'Upload a Gerber package to generate the real board preview and layer manifest.',
    );
    expect(screen.queryByRole('img', { name: 'Top Gerber board render' })).toBeNull();
  });

  it('surfaces parser failures as a friendly missing-preview state', () => {
    const runtime = makeRuntime();
    const context = runtime.getContext() as any;
    context.state.selectedLine = {
      pid: 'LINE-PARSE-FAILED',
      qo_ql_description: 'Gerber parser failed',
      qo_ql_board_width_mm: 88,
      qo_ql_board_height_mm: 42,
      qo_ql_gerber_parse_status: 'failed',
      qo_ql_gerber_validation_status: 'failed',
      qo_ql_gerber_validation_messages: ['Gerber parser could not identify a board outline.'],
    };
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: {
        project: { code: 'PARSE-FAILED', name: 'Parser failure quote line' },
        board: { widthMm: 88, heightMm: 42 },
        summary: {},
        layerManifest: [],
        components: [],
      },
      lineContext: '${state.selectedLine}',
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('gerber-metric-parse')).toHaveTextContent('failed / failed');
    expect(screen.getByTestId('gerber-validation-summary')).toHaveTextContent(
      'Validation errors found',
    );
    expect(screen.getByTestId('gerber-svg-unavailable')).toHaveTextContent(
      'Gerber parsing needs review',
    );
    expect(screen.getByTestId('gerber-svg-unavailable')).toHaveTextContent(
      'No real board preview was generated.',
    );
    expect(screen.getByTestId('gerber-svg-unavailable')).toHaveTextContent(
      'Gerber parser could not identify a board outline.',
    );
    expect(screen.queryByTestId('gerber-marker-C4')).toBeNull();
  });

  it('lets selected quote-line Gerber facts override the inline sample', () => {
    const runtime = makeRuntime();
    const context = runtime.getContext() as any;
    context.state.selectedLine = {
      pid: 'LINE-REAL',
      qo_ql_description: 'Uploaded corrected PCBA package',
      qo_ql_smt_points: 17,
      qo_ql_tht_points: 9,
      qo_ql_board_width_mm: 120,
      qo_ql_board_height_mm: 45,
      qo_ql_gerber_parse_status: 'parsed',
      qo_ql_gerber_validation_status: 'warning',
      qo_ql_gerber_validation_messages: ['NO_EXCELLON_FILE'],
    };
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: INSPECTION,
      lineContext: '${state.selectedLine}',
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('gerber-metric-board')).toHaveTextContent('120 x 45 mm');
    expect(screen.getByTestId('gerber-metric-smt-tht')).toHaveTextContent('17 / 9');
    expect(screen.getByTestId('gerber-metric-drill')).toHaveTextContent('9');
    expect(screen.getByTestId('gerber-issue-NO_EXCELLON_FILE-0')).toHaveTextContent(
      'NO_EXCELLON_FILE',
    );
    expect(screen.getByTestId('gerber-layer-smt_points')).toHaveTextContent('17');
  });

  it('renders real board SVG artifacts for top and bottom side views', () => {
    const runtime = makeRuntime();
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: {
        ...INSPECTION,
        boardSvgUrls: {
          top: '/artifacts/a00104001-board-top.svg',
          bottom: '/artifacts/a00104001-board-bottom.svg',
        },
      },
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    const topRender = screen.getByRole('img', { name: 'Top Gerber board render' });
    expect(topRender).toHaveAttribute('src', '/artifacts/a00104001-board-top.svg');
    expect(screen.getByRole('button', { name: 'Top' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('gerber-marker-C4')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Bottom' }));
    const bottomRender = screen.getByRole('img', { name: 'Bottom Gerber board render' });
    expect(bottomRender).toHaveAttribute('src', '/artifacts/a00104001-board-bottom.svg');
    expect(screen.getByTestId('gerber-marker-J1')).toBeInTheDocument();
  });

  it('normalizes stored file pid SVG URLs and loads them through authenticated fetch', async () => {
    const { createObjectURL, fetchMock, objectUrl } = stubBoardImageFetch();
    const runtime = makeRuntime();
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: {
        ...INSPECTION,
        boardSvgUrls: {
          top: '/01KV22CQ7PKX3W50Y7MM575ACK.svg',
        },
      },
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('/api/file/download/01KV22CQ7PKX3W50Y7MM575ACK', {
        credentials: 'include',
      });
    });
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('img', { name: 'Top Gerber board render' })).toHaveAttribute(
      'src',
      objectUrl,
    );
    expect(screen.getByTestId('gerber-marker-C4')).toBeInTheDocument();
  });

  it('shows a real artifact loading error instead of markers when the authenticated SVG request fails', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401 });
    vi.stubGlobal('fetch', fetchMock);
    const runtime = makeRuntime();
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: {
        ...INSPECTION,
        boardSvgUrls: {
          top: '/01KV22CQ7PKX3W50Y7MM575ACK.svg',
        },
      },
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    await waitFor(() => {
      expect(screen.getByTestId('gerber-svg-unavailable')).toHaveTextContent(
        'Board preview file could not be loaded',
      );
    });
    expect(screen.getByTestId('gerber-svg-unavailable')).toHaveTextContent('HTTP 401');
    expect(screen.getByTestId('gerber-svg-unavailable')).toHaveTextContent(
      'No generated preview is shown.',
    );
    expect(screen.getByTestId('gerber-viewer-board')).not.toHaveClass('bg-gray-950');
    expect(screen.queryByRole('img', { name: 'Top Gerber board render' })).toBeNull();
    expect(screen.queryByTestId('gerber-marker-C4')).toBeNull();
  });

  it('renders persisted line inspection JSON before falling back to the DSL sample', () => {
    const runtime = makeRuntime();
    const context = runtime.getContext() as any;
    context.state.selectedLine = {
      pid: 'LINE-INSPECTION',
      qo_ql_description: 'Persisted inspection',
      qo_ql_gerber_inspection: JSON.stringify({
        project: { code: 'REAL-INSPECTION', name: 'Persisted sidecar inspection' },
        board: { widthMm: 42, heightMm: 24 },
        boardSvgUrls: {
          top: '/line-artifacts/line-board-top.svg',
          bottom: '/line-artifacts/line-board-bottom.svg',
        },
        summary: { bomRefCount: 1, cplRefCount: 1, smdCount: 1, thtCount: 0 },
        components: [
          {
            refdes: 'R7',
            footprint: '0402',
            xMm: 10,
            yMm: 8,
            side: 'top',
            smd: true,
            bomItem: { materialName: 'Resistor', process: 'SMT' },
          },
        ],
      }),
    };
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: INSPECTION,
      lineContext: '${state.selectedLine}',
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('gerber-viewer')).toHaveTextContent('REAL-INSPECTION');
    expect(screen.getByTestId('gerber-metric-board')).toHaveTextContent('42 x 24 mm');
    expect(screen.getByTestId('gerber-marker-R7')).toBeInTheDocument();
    expect(screen.queryByTestId('gerber-marker-C4')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Top' }));
    expect(screen.getByRole('img', { name: 'Top Gerber board render' })).toHaveAttribute(
      'src',
      '/line-artifacts/line-board-top.svg',
    );
  });

  it('promotes informational inspection issues when the quote line validation status requires review', () => {
    const runtime = makeRuntime();
    const context = runtime.getContext() as any;
    context.state.selectedLine = {
      pid: 'LINE-WARNING',
      qo_ql_description: 'Persisted inspection warning',
      qo_ql_gerber_parse_status: 'parsed',
      qo_ql_gerber_validation_status: 'warning',
      qo_ql_gerber_inspection: JSON.stringify({
        project: { code: 'LINE-WARNING', name: 'Warning status inspection' },
        board: { widthMm: 42, heightMm: 24 },
        summary: { bomRefCount: 1, cplRefCount: 1, smdCount: 1, thtCount: 0 },
        issues: [
          {
            severity: 'info',
            code: 'EXCLUDED_NON_PLACEMENT_BOM_REF',
            refdes: 'P5',
            message: 'P5 is excluded from placement matching.',
          },
        ],
        components: [],
      }),
    };
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: INSPECTION,
      lineContext: '${state.selectedLine}',
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('gerber-metric-parse')).toHaveTextContent('parsed / warning');
    expect(screen.getByTestId('gerber-validation-summary')).toHaveTextContent(
      'Validation warnings found',
    );
    expect(screen.getByTestId('gerber-validation-summary')).toHaveTextContent('Warnings 1');
    expect(screen.getByTestId('gerber-issue-EXCLUDED_NON_PLACEMENT_BOM_REF-P5')).toHaveTextContent(
      'P5',
    );
  });

  it('falls back to a parsed line from the bound data source when the selected line only has default zero Gerber counts', () => {
    const runtime = makeRuntime({
      data: {
        lines: [
          {
            pid: 'LINE-UNPARSED',
            qo_ql_description: 'Unparsed resistor',
          },
          {
            pid: 'LINE-PARSED',
            qo_ql_description: 'Parsed MCU package',
            qo_ql_gerber_parse_status: 'parsed',
            qo_ql_gerber_validation_status: 'failed',
            qo_ql_gerber_inspection: JSON.stringify({
              project: { code: 'REAL-LINE-INSPECTION', name: 'Runtime sidecar result' },
              board: { widthMm: 106.6, heightMm: 6.6 },
              summary: {
                bomRefCount: 5,
                cplRefCount: 5,
                smdCount: 0,
                thtCount: 5,
                errorCount: 5,
                warningCount: 4,
              },
              issues: [
                {
                  severity: 'error',
                  code: 'COMPONENT_OUTSIDE_BOARD',
                  refdes: 'ORPHAN',
                  message: 'ORPHAN coordinate is outside the board outline.',
                },
              ],
              components: [
                {
                  refdes: 'ORPHAN',
                  footprint: 'R0603',
                  xMm: 200,
                  yMm: 0.1,
                  side: 'top',
                  smd: false,
                  issues: [
                    {
                      severity: 'error',
                      code: 'COMPONENT_OUTSIDE_BOARD',
                      refdes: 'ORPHAN',
                      message: 'ORPHAN coordinate is outside.',
                    },
                  ],
                  bomItem: { materialName: 'Missing BOM', process: 'SMT' },
                },
              ],
            }),
          },
        ],
      },
    });
    const context = runtime.getContext() as any;
    context.state.selectedLine = {
      pid: 'LINE-UNPARSED',
      qo_ql_description: 'Unparsed resistor',
      qo_ql_smt_points: 0,
      qo_ql_tht_points: 0,
    };
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      dataSource: 'lines',
      inspection: INSPECTION,
      lineContext: '${state.selectedLine}',
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('gerber-viewer')).toHaveTextContent('LINE-PARSED');
    expect(screen.getByTestId('gerber-viewer')).toHaveTextContent('Parsed MCU package');
    expect(screen.getByTestId('gerber-viewer')).not.toHaveTextContent('A00104001');
    expect(screen.getByTestId('gerber-metric-board')).toHaveTextContent('107 x 7 mm');
    expect(screen.getByTestId('gerber-issue-COMPONENT_OUTSIDE_BOARD-ORPHAN')).toHaveTextContent(
      'ORPHAN',
    );
    expect(screen.getByTestId('gerber-issue-COMPONENT_OUTSIDE_BOARD-ORPHAN')).toHaveTextContent(
      'COMPONENT_OUTSIDE_BOARD',
    );
    expect(screen.queryByTestId('gerber-marker-C4')).toBeNull();
  });

  it('filters issues and markers by severity and search query', () => {
    const runtime = makeRuntime();
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: {
        ...INSPECTION,
        boardSvgUrls: {
          top: '/artifacts/a00104001-board-top.svg',
          bottom: '/artifacts/a00104001-board-bottom.svg',
        },
      },
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    fireEvent.click(screen.getByRole('button', { name: 'Warnings' }));
    expect(screen.queryByTestId('gerber-issue-outside_board-U1')).toBeNull();
    expect(screen.getByTestId('gerber-issue-bom_process_mismatch-J1')).toHaveTextContent('J1');
    expect(screen.queryByTestId('gerber-marker-U1')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'All' }));
    fireEvent.click(screen.getByRole('button', { name: 'Bottom' }));
    fireEvent.change(screen.getByLabelText('Gerber viewer search'), {
      target: { value: 'Header' },
    });
    expect(screen.getByTestId('gerber-component-row-J1')).toHaveTextContent('Header');
    expect(screen.queryByTestId('gerber-component-row-C4')).toBeNull();
  });

  it('updates selected component details when a board marker is clicked', async () => {
    const runtime = makeRuntime();
    const block: BlockConfig = {
      id: 'gerber',
      blockType: 'gerber-viewer',
      inspection: {
        ...INSPECTION,
        boardSvgUrls: {
          top: '/artifacts/a00104001-board-top.svg',
        },
      },
    };

    render(<GerberViewerBlockRenderer block={block} runtime={runtime} />);

    fireEvent.click(screen.getByTestId('gerber-marker-U1'));

    await waitFor(() => {
      expect(screen.getByTestId('gerber-selected-component')).toHaveTextContent('U1');
      expect(screen.getByTestId('gerber-selected-component')).toHaveTextContent('QFN32');
    });
  });
});
