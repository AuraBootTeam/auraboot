import { useState } from 'react';
import { Link } from 'react-router';
import type { DocNavItem } from '../lib/mdx.server';

interface DocsSidebarProps {
  items: DocNavItem[];
  activeSlug: string;
}

function formatCategoryName(cat: string): string {
  return cat
    .split('-')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

export function DocsSidebar({ items, activeSlug }: DocsSidebarProps) {
  // Group items by category
  const grouped = items.reduce<Record<string, DocNavItem[]>>((acc, item) => {
    if (!acc[item.category]) acc[item.category] = [];
    acc[item.category].push(item);
    return acc;
  }, {});

  // Track which categories are expanded — default all open
  const [expanded, setExpanded] = useState<Record<string, boolean>>(() => {
    const initial: Record<string, boolean> = {};
    for (const cat of Object.keys(grouped)) {
      initial[cat] = true;
    }
    return initial;
  });

  const toggleCategory = (cat: string) => {
    setExpanded((prev) => ({ ...prev, [cat]: !prev[cat] }));
  };

  if (items.length === 0) {
    return (
      <div className="text-sm text-gray-500">
        No documentation pages found.
      </div>
    );
  }

  return (
    <nav className="space-y-6">
      {Object.entries(grouped).map(([category, categoryItems]) => (
        <div key={category}>
          <button
            type="button"
            onClick={() => toggleCategory(category)}
            className="flex w-full items-center justify-between text-xs font-bold uppercase tracking-wider text-gray-500 hover:text-gray-900 transition-colors"
          >
            <span>{formatCategoryName(category)}</span>
            <svg
              className={`h-3.5 w-3.5 transition-transform ${expanded[category] ? 'rotate-0' : '-rotate-90'}`}
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 9l-7 7-7-7"
              />
            </svg>
          </button>

          {expanded[category] && (
            <ul className="mt-2 space-y-1">
              {categoryItems.map((item) => {
                const isActive = item.slug === activeSlug;
                return (
                  <li key={item.slug}>
                    <Link
                      to={`/docs/${item.slug}`}
                      className={`block rounded-md px-3 py-1.5 text-sm transition-colors ${
                        isActive
                          ? 'border-l-2 border-purple-600 bg-purple-50 font-medium text-purple-700'
                          : 'text-gray-700 hover:bg-gray-100 hover:text-gray-900'
                      }`}
                    >
                      {item.title}
                    </Link>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      ))}
    </nav>
  );
}
