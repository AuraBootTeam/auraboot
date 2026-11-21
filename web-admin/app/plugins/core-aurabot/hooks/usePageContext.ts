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

/**
 * /p/:pageKey routes carry the PAGE key, which by convention is
 * `{model_code}_list` on list pages (see app/routes/p.$pageKey.tsx). Scene
 * tables (AuraBot suggestions, contributed providers) key by the bare model
 * code — derive it by stripping the known kind suffix, keeping pageKey intact.
 */
function toModelCode(pageKey: string): string {
  return pageKey.replace(/_(list|form|detail)$/, '');
}

export function deriveContextFromRoute(
  pathname: string,
  params: Record<string, string | undefined>,
): PageContext {
  // /p/:pageKey/view/:recordPid → detail
  if (pathname.match(/^\/p\/[^/]+\/view\/[^/]+/)) {
    const pageKey = params.pageKey || '';
    const recordPid = params.recordPid ?? params.recordId;
    return {
      pageType: 'detail',
      pageKey,
      modelCode: toModelCode(pageKey),
      recordPid,
      breadcrumb: [pageKey],
    };
  }

  // /p/:pageKey/edit/:recordPid → form (edit)
  if (pathname.match(/^\/p\/[^/]+\/edit\/[^/]+/)) {
    const pageKey = params.pageKey || '';
    const recordPid = params.recordPid ?? params.recordId;
    return {
      pageType: 'form',
      pageKey,
      modelCode: toModelCode(pageKey),
      recordPid,
      breadcrumb: [pageKey],
    };
  }

  // /p/:pageKey/new → form (create)
  if (pathname.match(/^\/p\/[^/]+\/new/)) {
    const pageKey = params.pageKey || '';
    return {
      pageType: 'form',
      pageKey,
      modelCode: toModelCode(pageKey),
      breadcrumb: [pageKey],
    };
  }

  // /p/:pageKey → list
  if (pathname.match(/^\/p\/[^/]+$/)) {
    const pageKey = params.pageKey || '';
    return {
      pageType: 'list',
      pageKey,
      modelCode: toModelCode(pageKey),
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
