/**
 * BPMN Palette — thin wrapper around shared DesignerPalette.
 */

import { useMemo } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { BPMN_PALETTE_ITEMS } from '~/bpmn-designer/constants';
import type { BPMNPaletteItem } from '~/bpmn-designer/types';
import { DesignerPalette } from '~/shared/designer';
import type { PaletteItem } from '~/shared/designer';

interface BPMNPaletteProps {
  onDragStart: (event: React.DragEvent, item: BPMNPaletteItem) => void;
}

// i18n key mapping for palette item types — resolved at render time via t()
const PALETTE_ITEM_I18N: Record<string, { label: string; description: string }> = {
  START_EVENT: { label: 'bpmn.palette.startEvent', description: 'bpmn.palette.startEventDesc' },
  END_EVENT: { label: 'bpmn.palette.endEvent', description: 'bpmn.palette.endEventDesc' },
  USER_TASK: { label: 'bpmn.palette.userTask', description: 'bpmn.palette.userTaskDesc' },
  SERVICE_TASK: { label: 'bpmn.palette.serviceTask', description: 'bpmn.palette.serviceTaskDesc' },
  RECEIVE_TASK: { label: 'bpmn.palette.receiveTask', description: 'bpmn.palette.receiveTaskDesc' },
  EXCLUSIVE_GATEWAY: { label: 'bpmn.palette.exclusiveGateway', description: 'bpmn.palette.exclusiveGatewayDesc' },
  PARALLEL_GATEWAY: { label: 'bpmn.palette.parallelGateway', description: 'bpmn.palette.parallelGatewayDesc' },
  INCLUSIVE_GATEWAY: { label: 'bpmn.palette.inclusiveGateway', description: 'bpmn.palette.inclusiveGatewayDesc' },
  CALL_ACTIVITY: { label: 'bpmn.palette.callActivity', description: 'bpmn.palette.callActivityDesc' },
};

export function BPMNPalette({ onDragStart }: BPMNPaletteProps) {
  const { t } = useI18n();

  const categoryLabels: Record<string, string> = useMemo(() => ({
    event: t('bpmn.palette.categoryEvent'),
    task: t('bpmn.palette.categoryTask'),
    gateway: t('bpmn.palette.categoryGateway'),
  }), [t]);

  const items: PaletteItem[] = useMemo(
    () =>
      BPMN_PALETTE_ITEMS.map((item) => {
        const i18nKeys = PALETTE_ITEM_I18N[item.type];
        return {
          type: item.type,
          label: i18nKeys ? t(i18nKeys.label) : item.label,
          icon: <span className="text-2xl">{item.icon}</span>,
          description: i18nKeys ? t(i18nKeys.description) : item.description,
          category: item.category,
          data: item,
        };
      }),
    [t],
  );

  return (
    <DesignerPalette
      items={items}
      title={t('bpmn.palette.title')}
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
