import { useState } from 'react';
import { useLocation, useParams } from 'react-router';

export interface PageContext {
  pageType: 'list' | 'detail' | 'form' | 'dashboard' | 'custom';
  pageKey: string;
  modelCode: string;
  recordPid?: string;
  recordData?: Record<string, any>;
  breadcrumb: string[];
}

/**
 * Hook that auto-detects page context from current route.
 * Components can inject additional context (like recordData) via setPageContext.
 */
export function usePageContext() {
  const location = useLocation();
  const params = useParams();
  const [extraContext, setExtraContext] = useState<Partial<PageContext>>({});

  const autoContext = deriveContextFromRoute(location.pathname, params);

  const pageContext: PageContext = {
    ...autoContext,
    ...extraContext,
    recordData: { ...autoContext.recordData, ...extraContext.recordData },
  };

  return { pageContext, setPageContext: setExtraContext };
}

function deriveContextFromRoute(
  pathname: string,
  params: Record<string, string | undefined>,
): PageContext {
  // /dynamic/:tableName/view/:recordId → detail
  if (pathname.match(/^\/dynamic\/[^/]+\/view\/[^/]+/)) {
    const tableName = params.tableName || '';
    return {
      pageType: 'detail',
      pageKey: tableName,
      modelCode: tableName.replace(/-/g, '_'),
      recordPid: params.recordId,
      breadcrumb: [tableName],
    };
  }

  // /dynamic/:tableName/:recordId/edit → form (edit)
  if (pathname.match(/^\/dynamic\/[^/]+\/[^/]+\/edit/)) {
    const tableName = params.tableName || '';
    return {
      pageType: 'form',
      pageKey: tableName,
      modelCode: tableName.replace(/-/g, '_'),
      recordPid: params.recordId,
      breadcrumb: [tableName],
    };
  }

  // /dynamic/:tableName/new → form (create)
  if (pathname.match(/^\/dynamic\/[^/]+\/new/)) {
    const tableName = params.tableName || '';
    return {
      pageType: 'form',
      pageKey: tableName,
      modelCode: tableName.replace(/-/g, '_'),
      breadcrumb: [tableName],
    };
  }

  // /dynamic/:tableName → list
  if (pathname.match(/^\/dynamic\/[^/]+$/)) {
    const tableName = params.tableName || '';
    return {
      pageType: 'list',
      pageKey: tableName,
      modelCode: tableName.replace(/-/g, '_'),
      breadcrumb: [tableName],
    };
  }

  // Dashboard pages
  if (pathname.includes('dashboard') || pathname.startsWith('/reports')) {
    return {
      pageType: 'dashboard',
      pageKey: pathname.split('/').pop() || 'dashboard',
      modelCode: '',
      breadcrumb: ['Dashboard'],
    };
  }

  // Default: custom page
  return {
    pageType: 'custom',
    pageKey: pathname,
    modelCode: '',
    breadcrumb: pathname.split('/').filter(Boolean),
  };
}

export default usePageContext;
