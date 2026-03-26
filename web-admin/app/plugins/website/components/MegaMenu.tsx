import { Link } from 'react-router';
import { useRef, useState, useEffect } from 'react';

export interface MegaMenuColumn {
  title: string;
  items: {
    icon: React.ReactNode;
    title: string;
    description: string;
    href: string;
    external?: boolean;
  }[];
}

interface MegaMenuProps {
  columns: MegaMenuColumn[];
  onMouseEnter: () => void;
  onMouseLeave: () => void;
}

export function MegaMenu({ columns, onMouseEnter, onMouseLeave }: MegaMenuProps) {
  const gridCols = columns.length <= 2 ? 'grid-cols-2' : 'grid-cols-3';
  const [visible, setVisible] = useState(false);
  const prevColumnsRef = useRef(columns);
  const [fadeKey, setFadeKey] = useState(0);

  // Panel entrance animation
  useEffect(() => {
    requestAnimationFrame(() => setVisible(true));
  }, []);

  // Content crossfade when columns change
  useEffect(() => {
    if (prevColumnsRef.current !== columns) {
      prevColumnsRef.current = columns;
      setFadeKey(k => k + 1);
    }
  }, [columns]);

  return (
    <div
      className={`absolute left-0 right-0 top-full z-50 transition-all duration-200 ${
        visible ? 'opacity-100 translate-y-0' : 'opacity-0 -translate-y-1'
      }`}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 pt-2">
        <div className="rounded-xl border border-gray-200 bg-white shadow-lg p-6">
          <div key={fadeKey} className={`grid ${gridCols} gap-x-8 gap-y-2 mega-menu-content`}>
            {columns.map((col) => (
              <div key={col.title}>
                <h3 className="mb-3 text-xs font-semibold uppercase tracking-wider text-gray-400">
                  {col.title}
                </h3>
                <div className="space-y-1">
                  {col.items.map((item) => {
                    const content = (
                      <div className="flex items-start gap-3 rounded-lg p-2.5 transition-colors duration-150 hover:bg-gray-50 cursor-pointer">
                        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-100 text-gray-600">
                          {item.icon}
                        </div>
                        <div className="min-w-0">
                          <div className="flex items-center gap-1.5">
                            <span className="text-sm font-medium text-gray-900">{item.title}</span>
                            {item.external && (
                              <svg className="h-3 w-3 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                              </svg>
                            )}
                          </div>
                          <p className="mt-0.5 text-sm text-gray-500 leading-snug">{item.description}</p>
                        </div>
                      </div>
                    );

                    if (item.external) {
                      return (
                        <a key={item.title} href={item.href} target="_blank" rel="noopener noreferrer">
                          {content}
                        </a>
                      );
                    }
                    return (
                      <Link key={item.title} to={item.href}>
                        {content}
                      </Link>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
