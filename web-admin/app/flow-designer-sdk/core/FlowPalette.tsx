// web-admin/app/flow-designer-sdk/core/FlowPalette.tsx
import React, { useMemo } from 'react';
import * as LucideIcons from 'lucide-react';
import { useSmartText } from '~/utils/i18n';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { DesignerPalette } from '~/shared/designer';
import type { PaletteItem } from '~/shared/designer';

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

/**
 * Render a lucide icon by name, falling back to the raw string or a default icon.
 */
function renderNodeIcon(icon: string | undefined) {
  if (!icon) return <span>📦</span>;
  const LucideIcon = (LucideIcons as unknown as Record<string, React.ElementType>)[icon];
  if (LucideIcon) return <LucideIcon className="h-4 w-4" />;
  return <span>{icon}</span>;
}

export function FlowPalette({ categoryOrder, className }: FlowPaletteProps) {
  const st = useSmartText();

  const categories = categoryOrder || nodeRegistry.getCategories();
  const grouped = nodeRegistry.getByCategory();

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
        result.push({
          type: def.type,
          label: st(def.label) || def.type,
          icon: renderNodeIcon(def.icon as string | undefined),
          description: def.description ? st(def.description) : undefined,
          category: def.category,
        });
      }
    }
    return result;
  }, [categories, grouped, st]);

  return (
    <DesignerPalette
      items={items}
      title={st('$i18n:flow.palette.title') || 'Components'}
      draggable
      dragMimeType="application/flow-node"
      categoryLabels={categoryLabels}
      categoryIcons={categoryIcons}
      categoryOrder={categories}
      emptyMessage={st('$i18n:flow.palette.empty') || 'No components available'}
      className={className || 'w-64'}
      testId="flow-palette"
    />
  );
}

export default FlowPalette;
