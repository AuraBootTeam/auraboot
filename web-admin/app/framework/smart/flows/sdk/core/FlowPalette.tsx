// web-admin/app/flow-designer-sdk/core/FlowPalette.tsx
import React, { useCallback, useMemo } from 'react';
import * as LucideIcons from 'lucide-react';
import { useSmartText } from '~/utils/i18n';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { useFlowStore } from '../store/useFlowStore';
import { humanizeType } from '../utils';
import { DesignerPalette } from '~/shared/designer';
import { resolveIcon } from '~/utils/icon-resolver';
import type { PaletteItem } from '~/shared/designer';
import type { FlowNodeDefinition, FlowNodeAvailabilityMetadata } from '../nodes/types';

export interface FlowPaletteProps {
  categoryOrder?: string[];
  className?: string;
}

const categoryLabelKeys: Record<string, string> = {
  trigger: '$i18n:flow.category.trigger',
  action: '$i18n:flow.category.action',
  control: '$i18n:flow.category.control',
};

const categoryIconComponents: Record<string, React.ElementType> = {
  trigger: LucideIcons.Zap,
  action: LucideIcons.Play,
  control: LucideIcons.GitBranch,
};

function renderNodeIcon(icon: unknown, label: string) {
  if (React.isValidElement(icon)) return icon;
  if (typeof icon === 'string') {
    const trimmed = icon.trim();
    if (trimmed.length > 0 && trimmed.length <= 2 && !/[A-Za-z0-9_-]/.test(trimmed)) {
      return <span aria-hidden="true">{trimmed}</span>;
    }
    return resolveIcon(trimmed || null, label, 16);
  }
  return resolveIcon(null, label, 16);
}

function resolveSmartText(
  st: (key: string) => string,
  key: string,
  fallback: string,
) {
  const value = st(key);
  return value && value !== key ? value : fallback;
}

function nodeAvailability(def: FlowNodeDefinition): FlowNodeAvailabilityMetadata | undefined {
  const availability = def.metadata?.availability;
  return availability && typeof availability === 'object' ? availability : undefined;
}

export function FlowPalette({ categoryOrder, className }: FlowPaletteProps) {
  const st = useSmartText();
  const { registryVersion, nodes, addNode, selectNode } = useFlowStore();

  // Re-read from registry whenever registryVersion changes (after registerAll)
  const categories = useMemo(() => categoryOrder || nodeRegistry.getCategories(), [categoryOrder, registryVersion]);
  const grouped = useMemo(() => nodeRegistry.getByCategory(), [registryVersion]);

  // Build category labels (resolved through i18n)
  const categoryLabels = useMemo(() => {
    const labels: Record<string, string> = {};
    for (const cat of categories) {
      labels[cat] = st(categoryLabelKeys[cat]) || cat;
    }
    return labels;
  }, [categories, st]);

  // Build category icons
  const categoryIcons = useMemo(() => {
    const icons: Record<string, React.ReactNode> = {};
    for (const cat of categories) {
      const Icon = categoryIconComponents[cat];
      if (Icon) icons[cat] = <Icon className="h-4 w-4" />;
    }
    return icons;
  }, [categories]);

  // Map registry nodes to PaletteItem[]
  const items: PaletteItem[] = useMemo(() => {
    const result: PaletteItem[] = [];
    for (const cat of categories) {
      const nodes = grouped[cat];
      if (!nodes?.length) continue;
      for (const def of nodes) {
        const label = st(def.label) || humanizeType(def.type);
        const availability = nodeAvailability(def);
        const unavailable = availability?.unavailable === true;
        result.push({
          type: def.type,
          label,
          icon: renderNodeIcon(def.icon, label),
          description: def.description ? st(def.description) : undefined,
          category: def.category,
          statusLabel: unavailable
            ? resolveSmartText(st, '$i18n:flow.availability.unavailable', '不可用')
            : undefined,
          statusText: unavailable ? availability?.reason : undefined,
          statusTone: unavailable ? 'warning' : undefined,
        });
      }
    }
    return result;
  }, [categories, grouped, st]);

  const handleItemClick = useCallback(
    (item: PaletteItem) => {
      const definition = nodeRegistry.get(item.type);
      if (!definition) return;

      const bounds = nodes.reduce<{ minX: number; maxY: number } | null>((acc, node) => {
        if (!acc) return { minX: node.position.x, maxY: node.position.y };
        return {
          minX: Math.min(acc.minX, node.position.x),
          maxY: Math.max(acc.maxY, node.position.y),
        };
      }, null);
      const position = bounds
        ? { x: bounds.minX, y: bounds.maxY + 120 }
        : { x: 120, y: 120 };
      const label = st(definition.label) || humanizeType(definition.type);
      const id = addNode({
        type: definition.type,
        position,
        data: {
          label,
          config: { ...(definition.defaultConfig || {}) },
        },
      });
      selectNode(id);
    },
    [addNode, nodes.length, selectNode, st],
  );

  return (
    <DesignerPalette
      items={items}
      title={st('$i18n:flow.palette.title') || 'Components'}
      draggable
      dragMimeType="application/flow-node"
      onItemClick={handleItemClick}
      categoryLabels={categoryLabels}
      categoryIcons={categoryIcons}
      categoryOrder={categories}
      emptyMessage={st('$i18n:flow.palette.empty') || 'No components available'}
      className={className || 'w-64'}
      testId="flow-palette"
      itemTestIdPrefix="palette-node"
    />
  );
}

export default FlowPalette;
