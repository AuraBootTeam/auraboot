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
  // /p/:pageKey/view/:recordId → detail
  if (pathname.match(/^\/p\/[^/]+\/view\/[^/]+/)) {
    const pageKey = params.pageKey || '';
    return {
      pageType: 'detail',
      pageKey,
      modelCode: pageKey,
      recordPid: params.recordId,
      breadcrumb: [pageKey],
    };
  }

  // /p/:pageKey/:recordId/edit → form (edit)
  if (pathname.match(/^\/p\/[^/]+\/[^/]+\/edit/)) {
    const pageKey = params.pageKey || '';
    return {
      pageType: 'form',
      pageKey,
      modelCode: pageKey,
      recordPid: params.recordId,
      breadcrumb: [pageKey],
    };
  }

  // /p/:pageKey/new → form (create)
  if (pathname.match(/^\/p\/[^/]+\/new/)) {
    const pageKey = params.pageKey || '';
    return {
      pageType: 'form',
      pageKey,
      modelCode: pageKey,
      breadcrumb: [pageKey],
    };
  }

  // /p/:pageKey → list
  if (pathname.match(/^\/p\/[^/]+$/)) {
    const pageKey = params.pageKey || '';
    return {
      pageType: 'list',
      pageKey,
      modelCode: pageKey,
      breadcrumb: [pageKey],
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
