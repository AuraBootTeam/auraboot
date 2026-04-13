/**
 * TemplatePreviewSidebar — Left panel showing template info and resource tree.
 *
 * Displays template icon/name/description at the top, followed by collapsible
 * resource group sections (Models, Commands, Pages, etc.). Each item is
 * clickable to show its details in the main content area.
 */

import { useState } from 'react';
import {
  CubeIcon,
  ListBulletIcon,
  CommandLineIcon,
  DocumentTextIcon,
  Bars3Icon,
  ShieldCheckIcon,
  TagIcon,
  CircleStackIcon,
  LanguageIcon,
  ChevronRightIcon,
} from '@heroicons/react/24/outline';
import type { AppTemplate } from './templateCatalog';
import type { PreviewGroup, ResourceChange } from './useTemplatePreview';

// ─── Icon resolver ───────────────────────────────────────────────────────────

const ICON_MAP: Record<string, React.ComponentType<React.SVGProps<SVGSVGElement>>> = {
  cube: CubeIcon,
  'list-bullet': ListBulletIcon,
  'command-line': CommandLineIcon,
  'document-text': DocumentTextIcon,
  'bars-3': Bars3Icon,
  'shield-check': ShieldCheckIcon,
  tag: TagIcon,
  'circle-stack': CircleStackIcon,
  language: LanguageIcon,
};

function GroupIcon({ icon, className }: { icon: string; className?: string }) {
  const Icon = ICON_MAP[icon] || CubeIcon;
  return <Icon className={className} />;
}

// ─── Props ───────────────────────────────────────────────────────────────────

export interface SelectedItem {
  type: string;
  code: string;
}

interface TemplatePreviewSidebarProps {
  template: AppTemplate;
  groups: PreviewGroup[];
  selectedItem: SelectedItem | null;
  onSelectItem: (item: SelectedItem | null) => void;
  loading: boolean;
}

// ─── Component ───────────────────────────────────────────────────────────────

export function TemplatePreviewSidebar({
  template,
  groups,
  selectedItem,
  onSelectItem,
  loading,
}: TemplatePreviewSidebarProps) {
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(
    () => new Set(groups.map((g) => g.type)),
  );

  const toggleGroup = (type: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return next;
    });
  };

  return (
    <aside
      className="flex w-[280px] flex-shrink-0 flex-col border-r border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800"
      data-testid="template-preview-sidebar"
    >
      {/* Template header */}
      <div className="border-b border-gray-100 p-5 dark:border-gray-700">
        <div className="mb-2 flex items-center gap-3">
          <span className="text-3xl" role="img">
            {template.icon}
          </span>
          <div className="min-w-0">
            <h2
              className="truncate text-base font-semibold text-gray-900 dark:text-white"
              data-testid="sidebar-template-name"
            >
              {template.name}
            </h2>
            <span className="text-xs text-gray-500 dark:text-gray-400">{template.category}</span>
          </div>
        </div>
        <p className="text-sm leading-relaxed text-gray-600 dark:text-gray-400">
          {template.description}
        </p>
      </div>

      {/* Resource tree */}
      <div className="flex-1 overflow-y-auto p-3" data-testid="sidebar-resource-tree">
        {loading ? (
          <div className="space-y-3 px-2 py-1">
            {[1, 2, 3].map((i) => (
              <div key={i} className="animate-pulse">
                <div className="mb-2 h-4 w-28 rounded bg-gray-200 dark:bg-gray-700" />
                <div className="ml-4 space-y-1.5">
                  <div className="h-3 w-24 rounded bg-gray-100 dark:bg-gray-700/50" />
                  <div className="h-3 w-20 rounded bg-gray-100 dark:bg-gray-700/50" />
                </div>
              </div>
            ))}
          </div>
        ) : groups.length === 0 ? (
          <p className="px-2 py-4 text-center text-sm text-gray-400 dark:text-gray-500">
            No resources found
          </p>
        ) : (
          <nav className="space-y-1">
            {groups.map((group) => {
              // Skip FIELD group in sidebar — fields are shown within model detail
              if (group.type === 'FIELD') return null;

              const isExpanded = expandedGroups.has(group.type);

              return (
                <div key={group.type}>
                  {/* Group header */}
                  <button
                    type="button"
                    onClick={() => toggleGroup(group.type)}
                    className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm font-medium text-gray-700 transition-colors hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700/50"
                    data-testid={`sidebar-group-${group.type.toLowerCase()}`}
                  >
                    <ChevronRightIcon
                      className={`h-3.5 w-3.5 flex-shrink-0 text-gray-400 transition-transform ${
                        isExpanded ? 'rotate-90' : ''
                      }`}
                    />
                    <GroupIcon
                      icon={group.icon}
                      className="h-4 w-4 flex-shrink-0 text-gray-500 dark:text-gray-400"
                    />
                    <span className="flex-1 truncate">{group.label}</span>
                    <span className="flex-shrink-0 rounded-full bg-gray-100 px-1.5 py-0.5 text-xs font-medium text-gray-600 dark:bg-gray-700 dark:text-gray-400">
                      {group.items.length}
                    </span>
                  </button>

                  {/* Group items */}
                  {isExpanded && (
                    <div className="mt-0.5 ml-5 space-y-0.5">
                      {group.items.map((item) => {
                        const isSelected =
                          selectedItem?.type === group.type &&
                          selectedItem?.code === item.resourceCode;

                        return (
                          <button
                            key={`${group.type}-${item.resourceCode}`}
                            type="button"
                            onClick={() =>
                              onSelectItem({ type: group.type, code: item.resourceCode })
                            }
                            className={`flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-sm transition-colors ${
                              isSelected
                                ? 'bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
                                : 'text-gray-600 hover:bg-gray-50 dark:text-gray-400 dark:hover:bg-gray-700/30'
                            }`}
                            data-testid={`sidebar-item-${item.resourceCode}`}
                          >
                            <span className="flex-1 truncate">
                              {item.resourceName || item.resourceCode}
                            </span>
                            <ActionBadge action={item.action} />
                          </button>
                        );
                      })}
                    </div>
                  )}
                </div>
              );
            })}
          </nav>
        )}
      </div>
    </aside>
  );
}

// ─── Action badge ────────────────────────────────────────────────────────────

function ActionBadge({ action }: { action: string }) {
  const colors: Record<string, string> = {
    CREATE: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    UPDATE: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
    SKIP: 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400',
  };

  return (
    <span
      className={`flex-shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium uppercase ${
        colors[action] || colors.SKIP
      }`}
    >
      {action}
    </span>
  );
}
