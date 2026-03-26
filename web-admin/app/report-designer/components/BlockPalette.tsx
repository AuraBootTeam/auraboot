/**
 * Block Palette — thin wrapper around shared DesignerPalette.
 * Uses click mode (report blocks are added by clicking, not dragging).
 */

import React, { useMemo } from 'react';
import { useReportStore } from '../store/useReportStore';
import type { BlockDefinition, ReportBand } from '../types';
import { DesignerPalette } from '~/shared/designer';
import type { PaletteItem } from '~/shared/designer';

// ==================== Block Definitions ====================

const BLOCK_DEFINITIONS: BlockDefinition[] = [
  {
    type: 'data-table',
    label: 'Data Table',
    icon: 'table',
    description: 'Tabular data from a model or query',
  },
  {
    type: 'grouped-table',
    label: 'Grouped Table',
    icon: 'grouped',
    description: 'Group rows by field with subtotals',
  },
  {
    type: 'stat-card',
    label: 'Stat Card',
    icon: 'stat',
    description: 'KPI metric with aggregation',
  },
  {
    type: 'rich-text',
    label: 'Rich Text',
    icon: 'text',
    description: 'Static text content or notes',
  },
  {
    type: 'cross-tab',
    label: 'Cross Tab',
    icon: 'crosstab',
    description: 'Pivot table with row/column grouping',
  },
  {
    type: 'chart',
    label: 'Chart',
    icon: 'chart',
    description: 'Bar, horizontal bar, or pie chart',
  },
  {
    type: 'barcode',
    label: 'Barcode',
    icon: 'barcode',
    description: 'Barcode from static value or data field',
  },
  {
    type: 'watermark',
    label: 'Watermark',
    icon: 'watermark',
    description: 'Text watermark overlay on the page',
  },
  {
    type: 'page-header',
    label: 'Page Header',
    icon: 'header',
    description: 'Repeated on every page top',
  },
  {
    type: 'page-footer',
    label: 'Page Footer',
    icon: 'footer',
    description: 'Repeated on every page bottom',
  },
];

// ==================== Block Icon ====================

const BlockIcon: React.FC<{ type: string; className?: string }> = ({
  type,
  className = 'w-6 h-6',
}) => {
  switch (type) {
    case 'table':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M3 10h18M3 14h18M3 6h18M3 18h18M9 6v12M15 6v12"
          />
        </svg>
      );
    case 'header':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M4 6h16M4 6v2h16V6M4 12h16M4 16h10"
          />
        </svg>
      );
    case 'footer':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M4 8h10M4 12h16M4 18h16M4 16v2h16v-2"
          />
        </svg>
      );
    case 'grouped':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M3 6h18M3 10h18M7 14h14M7 18h14M3 14h2M3 18h2"
          />
        </svg>
      );
    case 'stat':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"
          />
        </svg>
      );
    case 'text':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M4 6h16M4 10h16M4 14h10M4 18h12"
          />
        </svg>
      );
    case 'crosstab':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M3 3h18v18H3V3zM3 9h18M3 15h18M9 3v18M15 3v18"
          />
        </svg>
      );
    case 'chart':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"
          />
        </svg>
      );
    case 'barcode':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M3 4h1v16H3V4zm3 0h2v16H6V4zm4 0h1v16h-1V4zm3 0h3v16h-3V4zm5 0h1v16h-1V4zm3 0h1v16h-1V4z"
          />
        </svg>
      );
    case 'watermark':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
          />
        </svg>
      );
    default:
      return null;
  }
};

// ==================== Component ====================

export const BlockPalette: React.FC = () => {
  const { addBlock, updateHeader, updateFooter, report } = useReportStore();

  const handleAddBlock = (def: BlockDefinition) => {
    if (def.type === 'data-table') {
      addBlock({
        blockType: 'data-table',
        title: 'New Table',
        dataSource: '',
        columns: [],
        showHeader: true,
        stripe: true,
        border: true,
      });
    } else if (def.type === 'grouped-table') {
      addBlock({
        blockType: 'grouped-table',
        title: 'Grouped Table',
        dataSource: '',
        groupByField: '',
        columns: [],
        showHeader: true,
        border: true,
      } as any);
    } else if (def.type === 'stat-card') {
      addBlock({
        blockType: 'stat-card',
        label: 'Metric',
        dataSource: '',
        valueField: '',
        aggregation: 'sum',
      } as any);
    } else if (def.type === 'rich-text') {
      addBlock({
        blockType: 'rich-text',
        content: '',
        align: 'left',
      } as any);
    } else if (def.type === 'cross-tab') {
      addBlock({
        blockType: 'cross-tab',
        title: 'Cross Tab',
        dataSource: '',
        rowField: '',
        columnField: '',
        valueField: '',
        aggregation: 'sum',
        showRowTotal: true,
        showColumnTotal: true,
      } as any);
    } else if (def.type === 'chart') {
      addBlock({
        blockType: 'chart',
        title: 'Chart',
        dataSource: '',
        chartType: 'bar',
        categoryField: '',
        valueField: '',
        aggregation: 'sum',
        width: 400,
        height: 240,
      } as any);
    } else if (def.type === 'barcode') {
      addBlock({
        blockType: 'barcode',
        format: 'code128',
        staticValue: '',
        width: 2,
        height: 60,
        displayValue: true,
        fontSize: 14,
      } as any);
    } else if (def.type === 'watermark') {
      addBlock({
        blockType: 'watermark',
        text: 'confidential',
        rotation: -30,
        opacity: 0.1,
        fontSize: 16,
        color: '#000000',
        repeat: true,
      } as any);
    } else if (def.type === 'page-header') {
      const header: ReportBand = report?.header || {
        height: 15,
        elements: [
          {
            type: 'text',
            content: report?.title || 'Report',
            align: 'center',
            style: { fontSize: 14, fontWeight: 'bold' },
          },
        ],
      };
      updateHeader(header);
    } else if (def.type === 'page-footer') {
      const footer: ReportBand = report?.footer || {
        height: 10,
        elements: [
          { type: 'page-number', align: 'right', style: { fontSize: 9 } },
          { type: 'date', align: 'left', style: { fontSize: 9 } },
        ],
      };
      updateFooter(footer);
    }
  };

  // Map block definitions to PaletteItems, with disabled state for already-added singletons
  const items: PaletteItem[] = useMemo(() => {
    return BLOCK_DEFINITIONS.map((def) => {
      const isAdded =
        (def.type === 'page-header' && !!report?.header) ||
        (def.type === 'page-footer' && !!report?.footer);

      return {
        type: def.type,
        label: def.label,
        icon: (
          <div
            className={`flex h-10 w-10 items-center justify-center rounded ${isAdded ? 'text-gray-400' : 'text-gray-500'}`}
          >
            <BlockIcon type={def.icon} />
          </div>
        ),
        description: def.description,
        disabled: isAdded,
        disabledText: isAdded ? 'Added' : undefined,
        data: def,
      };
    });
  }, [report?.header, report?.footer]);

  return (
    <DesignerPalette
      items={items}
      title="Blocks"
      subtitle="Click to add to report"
      onItemClick={(item) => handleAddBlock(item.data as BlockDefinition)}
      className="w-56 bg-gray-50"
      testId="block-palette"
    />
  );
};
