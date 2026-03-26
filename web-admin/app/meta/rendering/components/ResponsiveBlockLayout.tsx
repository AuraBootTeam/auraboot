/**
 * ResponsiveBlockLayout — responsive block container
 *
 * Uses useMediaQuery to switch layouts at breakpoints.
 * Tables switch to card view on mobile.
 */

import React, { useMemo } from 'react';

interface ResponsiveBlockLayoutProps {
  /** Block type for responsive behavior */
  blockType: string;
  /** Responsive config from DSL */
  responsive?: {
    sm?: { columns?: number; display?: 'card' | 'table' };
    md?: { columns?: number; display?: 'card' | 'table' };
    lg?: { columns?: number; display?: 'card' | 'table' };
  };
  children: React.ReactNode;
  className?: string;
}

function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = React.useState(() => {
    if (typeof window === 'undefined') return false;
    return window.matchMedia(query).matches;
  });

  React.useEffect(() => {
    if (typeof window === 'undefined') return;
    const mq = window.matchMedia(query);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, [query]);

  return matches;
}

export const ResponsiveBlockLayout: React.FC<ResponsiveBlockLayoutProps> = ({
  blockType,
  responsive,
  children,
  className = '',
}) => {
  const isSm = useMediaQuery('(max-width: 640px)');
  const isMd = useMediaQuery('(min-width: 641px) and (max-width: 1024px)');

  const responsiveClass = useMemo(() => {
    if (isSm && responsive?.sm) {
      const cols = responsive.sm.columns || 1;
      return `grid grid-cols-${cols} gap-4`;
    }
    if (isMd && responsive?.md) {
      const cols = responsive.md.columns || 2;
      return `grid grid-cols-${cols} gap-4`;
    }
    return '';
  }, [isSm, isMd, responsive]);

  const shouldUseCardView = useMemo(() => {
    if (blockType === 'data-table' || blockType === 'table') {
      if (isSm && responsive?.sm?.display === 'card') return true;
      if (isMd && responsive?.md?.display === 'card') return true;
    }
    return false;
  }, [blockType, isSm, isMd, responsive]);

  return (
    <div
      className={`${responsiveClass} ${className}`}
      data-responsive={shouldUseCardView ? 'card' : 'table'}
    >
      {children}
    </div>
  );
};

export { useMediaQuery };
