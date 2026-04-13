/**
 * Report Canvas — A4 paper preview with header, body blocks, footer.
 * Supports drag-and-drop reordering of body blocks.
 */

import React, { useCallback, useState, useRef } from 'react';
import { useReportStore } from '../store/useReportStore';
import { A4Page } from './A4Page';
import { ReportTableBlock } from '../blocks/ReportTableBlock';
import { ReportGroupedTableBlock } from '../blocks/ReportGroupedTableBlock';
import { ReportStatCardBlock } from '../blocks/ReportStatCardBlock';
import { ReportRichTextBlock } from '../blocks/ReportRichTextBlock';
import { ReportCrossTabBlock } from '../blocks/ReportCrossTabBlock';
import { ReportChartBlock } from '../blocks/ReportChartBlock';
import { ReportBarcodeBlock } from '../blocks/ReportBarcodeBlock';
import { ReportWatermarkBlock } from '../blocks/ReportWatermarkBlock';
import { ReportBandBlock } from '../blocks/ReportBandBlock';
import { DesignerEmptyState, DESIGNER_I18N } from '~/shared/designer';

/** GripVertical icon for drag handle */
const GripVertical: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
    <circle cx="9" cy="6" r="1" fill="currentColor" />
    <circle cx="15" cy="6" r="1" fill="currentColor" />
    <circle cx="9" cy="12" r="1" fill="currentColor" />
    <circle cx="15" cy="12" r="1" fill="currentColor" />
    <circle cx="9" cy="18" r="1" fill="currentColor" />
    <circle cx="15" cy="18" r="1" fill="currentColor" />
  </svg>
);

export const ReportCanvas: React.FC = () => {
  const { report, selectedBlockId, selectBlock, reorderBlock } = useReportStore();
  const [draggedBlockId, setDraggedBlockId] = useState<string | null>(null);
  const [dropTargetIndex, setDropTargetIndex] = useState<number | null>(null);
  const blockRefs = useRef<Map<string, HTMLDivElement>>(new Map());

  const handleDragStart = useCallback((e: React.DragEvent, blockId: string) => {
    setDraggedBlockId(blockId);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', blockId);
    // Make the dragged element semi-transparent
    const target = e.currentTarget as HTMLElement;
    requestAnimationFrame(() => {
      target.style.opacity = '0.5';
    });
  }, []);

  const handleDragEnd = useCallback((e: React.DragEvent) => {
    const target = e.currentTarget as HTMLElement;
    target.style.opacity = '1';
    setDraggedBlockId(null);
    setDropTargetIndex(null);
  }, []);

  const handleDragOver = useCallback(
    (e: React.DragEvent, index: number) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';

      if (!draggedBlockId || !report) return;

      // Calculate whether to insert before or after this block
      const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
      const midY = rect.top + rect.height / 2;
      const targetIdx = e.clientY < midY ? index : index + 1;

      setDropTargetIndex(targetIdx);
    },
    [draggedBlockId, report],
  );

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      if (!draggedBlockId || dropTargetIndex === null || !report) return;

      const currentIndex = report.body.findIndex((b) => b.id === draggedBlockId);
      // Adjust target index if dragging from before the target
      const adjustedTarget = currentIndex < dropTargetIndex ? dropTargetIndex - 1 : dropTargetIndex;

      if (currentIndex !== adjustedTarget) {
        reorderBlock(draggedBlockId, adjustedTarget);
      }

      setDraggedBlockId(null);
      setDropTargetIndex(null);
    },
    [draggedBlockId, dropTargetIndex, report, reorderBlock],
  );

  if (!report) return null;

  return (
    <div
      data-testid="report-canvas"
      className="flex-1 overflow-auto bg-gray-100 p-8"
      onClick={(e) => {
        if (e.target === e.currentTarget) selectBlock(null);
      }}
    >
      <A4Page page={report.page}>
        {/* Header */}
        {report.header && (
          <div
            className={`mb-4 cursor-pointer rounded transition-all ${
              selectedBlockId === '__header'
                ? 'ring-2 ring-blue-500'
                : 'hover:ring-1 hover:ring-blue-300'
            }`}
            onClick={(e) => {
              e.stopPropagation();
              selectBlock('__header');
            }}
          >
            <ReportBandBlock band={report.header} mode="design" position="header" />
          </div>
        )}

        {/* Separator line after header */}
        {report.header && <div className="mb-4 border-b border-gray-300" />}

        {/* Body blocks */}
        {report.body.length === 0 ? (
          <DesignerEmptyState
            variant="dashed"
            title={DESIGNER_I18N.emptyState.clickToAdd}
            testId="report-canvas-empty"
          />
        ) : (
          report.body.map((block, index) => (
            <React.Fragment key={block.id}>
              {/* Insertion line indicator */}
              {dropTargetIndex === index && draggedBlockId && (
                <div
                  data-testid="drop-indicator"
                  className="my-1 h-0.5 rounded-full bg-blue-500 shadow-sm shadow-blue-300"
                />
              )}
              <div
                ref={(el) => {
                  if (el) blockRefs.current.set(block.id, el);
                  else blockRefs.current.delete(block.id);
                }}
                className={`group relative mb-4 cursor-pointer rounded transition-all ${
                  selectedBlockId === block.id
                    ? 'ring-2 ring-blue-500'
                    : 'hover:ring-1 hover:ring-blue-300'
                } ${draggedBlockId === block.id ? 'opacity-50' : ''}`}
                onClick={(e) => {
                  e.stopPropagation();
                  selectBlock(block.id);
                }}
                draggable
                onDragStart={(e) => handleDragStart(e, block.id)}
                onDragEnd={handleDragEnd}
                onDragOver={(e) => handleDragOver(e, index)}
                onDrop={handleDrop}
              >
                {/* Drag handle */}
                <div
                  className="absolute top-2 left-2 z-10 cursor-grab rounded p-1 text-gray-400 opacity-0 transition-opacity group-hover:opacity-100 hover:bg-gray-100 hover:text-gray-600 active:cursor-grabbing"
                  data-testid={`drag-handle-${block.id}`}
                  onMouseDown={(e) => e.stopPropagation()}
                >
                  <GripVertical className="h-4 w-4" />
                </div>

                {block.blockType === 'table' && (
                  <ReportTableBlock block={block} mode="design" />
                )}
                {block.blockType === 'grouped-table' && (
                  <ReportGroupedTableBlock block={block} mode="design" />
                )}
                {block.blockType === 'stat-card' && (
                  <ReportStatCardBlock block={block} mode="design" />
                )}
                {block.blockType === 'rich-text' && (
                  <ReportRichTextBlock block={block} mode="design" />
                )}
                {block.blockType === 'cross-tab' && (
                  <ReportCrossTabBlock block={block} mode="design" />
                )}
                {block.blockType === 'chart' && <ReportChartBlock block={block} mode="design" />}
                {block.blockType === 'barcode' && (
                  <ReportBarcodeBlock block={block} mode="design" />
                )}
                {block.blockType === 'watermark' && (
                  <ReportWatermarkBlock block={block} mode="design" />
                )}
              </div>
            </React.Fragment>
          ))
        )}
        {/* Trailing insertion line */}
        {dropTargetIndex === report.body.length && draggedBlockId && (
          <div
            data-testid="drop-indicator"
            className="my-1 h-0.5 rounded-full bg-blue-500 shadow-sm shadow-blue-300"
          />
        )}

        {/* Separator line before footer */}
        {report.footer && <div className="mt-4 mb-4 border-b border-gray-300" />}

        {/* Footer */}
        {report.footer && (
          <div
            className={`mt-auto cursor-pointer rounded transition-all ${
              selectedBlockId === '__footer'
                ? 'ring-2 ring-blue-500'
                : 'hover:ring-1 hover:ring-blue-300'
            }`}
            onClick={(e) => {
              e.stopPropagation();
              selectBlock('__footer');
            }}
          >
            <ReportBandBlock band={report.footer} mode="design" position="footer" />
          </div>
        )}
      </A4Page>
    </div>
  );
};
