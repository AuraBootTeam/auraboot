/**
 * Report BlockRegistry (B1 Phase 1 PR-1b).
 *
 * A DEDICATED report block registry — the report surface's Layer-2 block
 * vocabulary (the 8 namespaced report-* block types). Reuses the shared Layer-0
 * BlockRegistryV3 class but is a SEPARATE instance from the default unified
 * registry, so report blocks never leak into / pollute the page/form/list
 * designers (DDR-2026-06-18: per-surface Layer-2 registry).
 *
 * Each definition's inspector is sourced from the report InspectorSchemaRegistry
 * (PropertySchema-driven, Layer-0), satisfying the Phase 1 DoD: "report blocks
 * via BlockRegistry; inspector via InspectorSchemaRegistry".
 */
import type { BlockDefinitionV3, InspectorSchemaV3 } from '../../unified-designer/types';
import { BlockRegistryV3 } from '../../unified-designer/registry/BlockRegistry';
import { reportInspectorSchemaRegistry } from './reportInspectorSchemas';

export const REPORT_BLOCK_TYPES = [
  'report-table',
  'report-grouped-table',
  'report-stat-card',
  'report-rich-text',
  'report-cross-tab',
  'report-chart',
  'report-barcode',
  'report-watermark',
] as const;

export type ReportBlockType = (typeof REPORT_BLOCK_TYPES)[number];

interface ReportBlockMeta {
  label: { 'en-US': string; 'zh-CN': string };
  icon: string;
}

const REPORT_BLOCK_META: Record<ReportBlockType, ReportBlockMeta> = {
  'report-table': { label: { 'en-US': 'Table', 'zh-CN': '表格' }, icon: 'table-2' },
  'report-grouped-table': {
    label: { 'en-US': 'Grouped table', 'zh-CN': '分组表格' },
    icon: 'table-properties',
  },
  'report-stat-card': { label: { 'en-US': 'Stat card', 'zh-CN': '指标卡' }, icon: 'gauge' },
  'report-rich-text': { label: { 'en-US': 'Rich text', 'zh-CN': '富文本' }, icon: 'file-text' },
  'report-cross-tab': { label: { 'en-US': 'Cross tab', 'zh-CN': '交叉表' }, icon: 'grid-3x3' },
  'report-chart': { label: { 'en-US': 'Chart', 'zh-CN': '图表' }, icon: 'chart-column' },
  'report-barcode': { label: { 'en-US': 'Barcode', 'zh-CN': '条码' }, icon: 'scan-barcode' },
  'report-watermark': { label: { 'en-US': 'Watermark', 'zh-CN': '水印' }, icon: 'droplets' },
};

function toInspectorSchema(blockType: string): InspectorSchemaV3 {
  return {
    tabs: [
      {
        key: 'basic',
        label: { 'en-US': 'Basic', 'zh-CN': '基础' },
        groups: [
          {
            key: 'main',
            label: { 'en-US': 'Main', 'zh-CN': '主要' },
            fields: reportInspectorSchemaRegistry.getFields(blockType),
          },
        ],
      },
    ],
  };
}

export function createReportBlockRegistry(): BlockRegistryV3 {
  const registry = new BlockRegistryV3();
  registry.registerAll(
    REPORT_BLOCK_TYPES.map((blockType): BlockDefinitionV3 => {
      const meta = REPORT_BLOCK_META[blockType];
      return {
        blockType,
        label: meta.label,
        icon: meta.icon,
        category: 'report',
        inspector: toInspectorSchema(blockType),
        layoutCapability: 'span',
      };
    }),
  );
  return registry;
}

export const reportBlockRegistry = createReportBlockRegistry();
