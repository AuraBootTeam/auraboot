/**
 * ReportDslCompatibilityAdapter (B1 Phase 1 — contract convergence, no canvas change).
 *
 * Converts the legacy ReportDsl (v1) into a normalized, block-tree-compatible
 * PageSchemaV3 plus a Layer-2 ReportLayoutProfile. This is the seam that lets the
 * report surface converge onto the unified-designer Block-tree family WITHOUT
 * rewriting the DSL or touching the canvas: the old report UI keeps reading the
 * original ReportDsl; this adapter produces the normalized target representation
 * that Phase 2/3 will feed into the shared kernel + export pipeline.
 *
 * DDR-2026-06-18-designer-kernel-boundary / backlog 2026-06-18 §B1.
 *
 * Mapping rules:
 *  - Each ReportBlock -> a DslBlockV3 node, blockType namespaced `report-<type>`
 *    so the report surface keeps a distinct Layer-2 block vocabulary and does NOT
 *    collide with / pollute the unified registry's generic table/chart/etc.
 *  - Paged-media concerns (page config, header/footer bands, parameters) go into
 *    the Layer-2 ReportLayoutProfile, NEVER as block-tree nodes (Forbidden:
 *    bands / page-break concepts into the Layer-1 kernel).
 *  - Report charts are expressed as renderer-agnostic ChartSpec (B2a), via the
 *    shared bindingFromFields adapter — proving report chart is a ChartSpec render
 *    target, not a second chart DSL.
 *  - The page-level shared dataSources map is preserved on the page extension; a
 *    block references its source by `{ ref }` rather than inlining (no duplication).
 */
import type { DslBlockV3, PageSchemaV3 } from '../../unified-designer/types';
import {
  bindingFromFields,
  type ChartSpecWarning,
} from '~/framework/smart/charts/chart-spec-adapter';
import type { ChartSpec, ChartSpecType } from '~/framework/smart/charts/chart-spec';
import type { ChartDataSource, FilterConfig } from '~/framework/smart/types/chart';
import type {
  ChartBlock,
  PageConfig,
  ReportBand,
  ReportBlock,
  ReportDataSource,
  ReportDsl,
  ReportParameter,
} from '../types';

/** Layer-2 paged-media profile — the report-specific layout state that must not
 * live in the Layer-1 block-tree kernel. */
export interface ReportLayoutProfile {
  page: PageConfig;
  header?: ReportBand;
  footer?: ReportBand;
  parameters?: ReportParameter[];
}

export interface ReportBlockTreeResult {
  /** Block-tree compatible schema (kind: composite, surface: report). */
  page: PageSchemaV3;
  /** Layer-2 paged-media profile (also mirrored on page.extension). */
  layoutProfile: ReportLayoutProfile;
  /** blockId -> ChartSpec for each report chart block. */
  charts: Record<string, ChartSpec>;
  /** Non-fatal conversion warnings (e.g. renderer-leak drops). */
  warnings: ChartSpecWarning[];
}

const DATA_SOURCE_TYPE_MAP: Record<ReportDataSource['type'], ChartDataSource['type']> = {
  model: 'aggregate',
  namedQuery: 'namedQuery',
  api: 'api',
  static: 'static',
};

/** Map a report (page-level) data source to a renderer-agnostic ChartDataSource. */
function toChartDataSource(ds: ReportDataSource | undefined): ChartDataSource {
  if (!ds) return { type: 'static' };
  const out: ChartDataSource = { type: DATA_SOURCE_TYPE_MAP[ds.type] };
  if (ds.modelCode) out.modelCode = ds.modelCode;
  if (ds.queryCode) out.queryCode = ds.queryCode;
  if (ds.url) out.url = ds.url;
  if (ds.type === 'static' && ds.data) out.staticData = ds.data;
  if (ds.filters) {
    out.filters = ds.filters.map((f) => ({
      field: f.field,
      operator: f.operator as FilterConfig['operator'],
      value: f.value,
    }));
  }
  return out;
}

/** Express a report ChartBlock as a renderer-agnostic ChartSpec. */
function reportChartToSpec(
  block: ChartBlock,
  ds: ReportDataSource | undefined,
): ChartSpec {
  const type: ChartSpecType = block.chartType === 'pie' ? 'pie' : 'bar';
  // For pie the category becomes the slice-name dimension; for bars it is the
  // category axis. bindingFromFields encodes both.
  const fieldBinding =
    type === 'pie'
      ? { nameField: block.categoryField, valueField: block.valueField }
      : { categoryField: block.categoryField, valueField: block.valueField };
  const { dimensions, measures } = bindingFromFields(fieldBinding, type);
  const withAgg = block.aggregation
    ? measures.map((m) => ({ ...m, aggregation: block.aggregation }))
    : measures;

  const spec: ChartSpec = {
    type,
    dataSource: toChartDataSource(ds),
    dimensions,
    measures: withAgg,
    interaction: { tooltip: true },
  };
  if (block.title) spec.title = block.title;
  if (block.chartType === 'horizontal-bar') spec.visual = { orientation: 'horizontal' };
  return spec;
}

/** Extract the report-specific props of a block (everything that is not the
 * structural id / blockType / title / dataSource). */
function toBlockProps(block: ReportBlock): Record<string, unknown> {
  const rec = { ...(block as unknown as Record<string, unknown>) };
  delete rec.id;
  delete rec.blockType;
  delete rec.title;
  delete rec.dataSource;
  return rec;
}

export function reportDslToBlockTree(
  dsl: ReportDsl,
  opts?: { pageId?: string },
): ReportBlockTreeResult {
  const charts: Record<string, ChartSpec> = {};
  const warnings: ChartSpecWarning[] = [];

  const blocks: DslBlockV3[] = dsl.body.map((block) => {
    const node: DslBlockV3 = {
      id: block.id,
      blockType: `report-${block.blockType}`,
    };
    if ('title' in block && block.title) node.title = block.title;

    const dsKey = (block as { dataSource?: string }).dataSource;
    if (typeof dsKey === 'string') node.dataSource = { ref: dsKey };

    if (block.blockType === 'chart') {
      const spec = reportChartToSpec(block, dsl.dataSources[block.dataSource]);
      charts[block.id] = spec;
      node.props = { chartSpec: spec };
    } else {
      const props = toBlockProps(block);
      if (Object.keys(props).length > 0) node.props = props;
    }
    return node;
  });

  const layoutProfile: ReportLayoutProfile = { page: dsl.page };
  if (dsl.header) layoutProfile.header = dsl.header;
  if (dsl.footer) layoutProfile.footer = dsl.footer;
  if (dsl.parameters) layoutProfile.parameters = dsl.parameters;

  const page: PageSchemaV3 = {
    schemaVersion: 3,
    kind: 'composite',
    id: opts?.pageId ?? 'report',
    title: dsl.title,
    blocks,
    extension: {
      surface: 'report',
      reportLayoutProfile: layoutProfile,
      reportDataSources: dsl.dataSources,
    },
  };

  return { page, layoutProfile, charts, warnings };
}
