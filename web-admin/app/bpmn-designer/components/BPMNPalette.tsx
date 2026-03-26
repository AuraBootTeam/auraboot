/**
 * BPMN Palette — thin wrapper around shared DesignerPalette.
 */

import { useMemo } from 'react';
import { BPMN_PALETTE_ITEMS } from '~/bpmn-designer/constants';
import type { BPMNPaletteItem } from '~/bpmn-designer/types';
import { DesignerPalette } from '~/shared/designer';
import type { PaletteItem } from '~/shared/designer';

interface BPMNPaletteProps {
  onDragStart: (event: React.DragEvent, item: BPMNPaletteItem) => void;
}

const categoryLabels: Record<string, string> = {
  event: '事件',
  task: '任务',
  gateway: '网关',
};

export function BPMNPalette({ onDragStart }: BPMNPaletteProps) {
  const items: PaletteItem[] = useMemo(
    () =>
      BPMN_PALETTE_ITEMS.map((item) => ({
        type: item.type,
        label: item.label,
        icon: <span className="text-2xl">{item.icon}</span>,
        description: item.description,
        category: item.category,
        data: item,
      })),
    [],
  );

  return (
    <DesignerPalette
      items={items}
      title="BPMN组件"
      draggable
      categoryLabels={categoryLabels}
      categoryOrder={['event', 'task', 'gateway']}
      onItemDragStart={(e, paletteItem) => {
        onDragStart(e, paletteItem.data as BPMNPaletteItem);
      }}
      className="w-64"
      testId="bpmn-palette"
    />
  );
}
