/**
 * TemplateCategorySidebar — Left sidebar for the Template Center.
 *
 * Provides category navigation with icons and a search input
 * that filters templates by name, tags, and description.
 */

import {
  MagnifyingGlassIcon,
  SparklesIcon,
  UsersIcon,
  Cog6ToothIcon,
  UserGroupIcon,
  CubeIcon,
  ArchiveBoxIcon,
} from '@heroicons/react/24/outline';
import type { TemplateCategoryDef } from './templateCatalog';

// Map icon name strings to heroicon components
const ICON_MAP: Record<string, React.ComponentType<React.SVGProps<SVGSVGElement>>> = {
  sparkles: SparklesIcon,
  users: UsersIcon,
  'cog-6-tooth': Cog6ToothIcon,
  'user-group': UserGroupIcon,
  cube: CubeIcon,
  'archive-box': ArchiveBoxIcon,
};

interface TemplateCategorySidebarProps {
  categories: TemplateCategoryDef[];
  activeCategory: string;
  onSelect: (categoryId: string) => void;
  searchQuery: string;
  onSearchChange: (query: string) => void;
}

export function TemplateCategorySidebar({
  categories,
  activeCategory,
  onSelect,
  searchQuery,
  onSearchChange,
}: TemplateCategorySidebarProps) {
  return (
    <div
      className="flex w-60 flex-shrink-0 flex-col border-r border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-900"
      data-testid="template-category-sidebar"
    >
      {/* Search */}
      <div className="p-4">
        <div className="relative">
          <MagnifyingGlassIcon className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            placeholder="Search templates..."
            value={searchQuery}
            onChange={(e) => onSearchChange(e.target.value)}
            className="w-full rounded-lg border border-gray-300 bg-white py-2 pl-9 pr-3 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500 dark:focus:border-blue-400"
            data-testid="template-search-input"
          />
        </div>
      </div>

      {/* Category list */}
      <nav className="flex-1 space-y-0.5 px-3 pb-4" data-testid="template-category-list">
        {categories.map((cat) => {
          const Icon = ICON_MAP[cat.icon] ?? SparklesIcon;
          const isActive = activeCategory === cat.id;

          return (
            <button
              key={cat.id}
              onClick={() => onSelect(cat.id)}
              data-testid={`template-category-${cat.id}`}
              className={`flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300'
                  : 'text-gray-700 hover:bg-gray-200 dark:text-gray-300 dark:hover:bg-gray-800'
              }`}
            >
              <Icon className={`h-5 w-5 flex-shrink-0 ${isActive ? 'text-blue-600 dark:text-blue-400' : 'text-gray-400 dark:text-gray-500'}`} />
              {cat.label}
            </button>
          );
        })}
      </nav>
    </div>
  );
}

export default TemplateCategorySidebar;
