/**
 * Catch-all route for plugin-defined menu paths.
 *
 * This route handles paths that don't match any static routes,
 * looking up the menu configuration and rendering the appropriate page.
 *
 * Flow:
 * 1. Get current path from URL
 * 2. Query menu API to find matching menu configuration
 * 3. If menu has pageKey, query page API to get page configuration
 * 4. Based on page config (modelCode, pageType), render dynamic CRUD page
 * 5. Otherwise, show 404
 */

import React, { useState, useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { useLoaderData } from 'react-router';
import { getTokenFromRequest } from '~/services/session';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';

interface MenuInfo {
  pid: string;
  name: string;
  path: string;
  pageKey?: string;
  pagePid?: string;
}

interface PageInfo {
  pid: string;
  pageKey?: string;
  modelCode?: string;
  kind?: string;
}

interface LoaderData {
  path: string;
  token: string | null;
}

export const loader = async ({ request }: LoaderFunctionArgs) => {
  const url = new URL(request.url);
  const path = url.pathname;

  try {
    const token = await getTokenFromRequest(request);
    return { path, token };
  } catch (error) {
    console.error('Failed to get token:', error);
    return { path, token: null };
  }
};

export default function CatchAllRoute() {
  const { path, token } = useLoaderData<LoaderData>();
  const navigate = useNavigate();
  const location = useLocation();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [menuInfo, setMenuInfo] = useState<MenuInfo | null>(null);
  const [redirecting, setRedirecting] = useState(false);
  const [renderPage, setRenderPage] = useState<{
    tableName: string;
    pageKey?: string;
    pageType: 'list' | 'form' | 'detail' | 'dashboard' | 'kanban';
  } | null>(null);

  useEffect(() => {
    const fetchMenuAndPageInfo = async () => {
      if (!token) {
        setError('Please login first');
        setLoading(false);
        return;
      }

      try {
        setLoading(true);
        setError(null);

        // Step 1: Query menu by path
        const menuResult = await fetchResult<MenuInfo>('/api/menu/by-path', {
          method: 'get',
          params: { path: location.pathname },
          token,
        });

        if (!ResultHelper.isSuccess(menuResult) || !menuResult.data) {
          setError(`Menu configuration not found for path "${location.pathname}"`);
          setLoading(false);
          return;
        }

        const menu = menuResult.data;
        setMenuInfo(menu);

        // Step 2: Get page configuration via pageKey or pagePid
        const pageKey = menu.pageKey;
        const pagePid = menu.pagePid;

        if (!pageKey && !pagePid) {
          setError(`Menu "${menu.name}" has no associated page configuration`);
          setLoading(false);
          return;
        }

        // Query page API to get modelCode and pageType
        let pageInfo: PageInfo | null = null;

        if (pageKey) {
          // Prefer pageKey lookup via /api/pages/key/{pageKey}
          const pageResult = await fetchResult<PageInfo>(`/api/pages/key/${pageKey}`, {
            method: 'get',
            token,
          });

          if (ResultHelper.isSuccess(pageResult) && pageResult.data) {
            pageInfo = pageResult.data;
          }
        }

        // Fallback to pagePid if pageKey lookup failed
        if (!pageInfo && pagePid) {
          const pageResult = await fetchResult<PageInfo>(`/api/pages/${pagePid}`, {
            method: 'get',
            token,
          });

          if (ResultHelper.isSuccess(pageResult) && pageResult.data) {
            pageInfo = pageResult.data;
          }
        }

        if (!pageInfo) {
          setError(`Page configuration not found for menu "${menu.name}"`);
          setLoading(false);
          return;
        }

        // Step 3: Resolve rendering strategy based on page configuration
        const { modelCode, kind } = pageInfo;
        // Pass kind directly — must match ab_page_schema.kind values
        const VALID_KINDS = ['list', 'form', 'detail', 'dashboard', 'kanban'] as const;
        type PageKind = (typeof VALID_KINDS)[number];
        const resolvedKind = (kind || 'list') as string;
        if (!VALID_KINDS.includes(resolvedKind as PageKind)) {
          console.error(
            `[CatchAllRoute] Invalid page kind "${resolvedKind}" for path "${location.pathname}". ` +
            `Expected one of: ${VALID_KINDS.join(', ')}. Check ab_page_schema.kind value.`
          );
        }
        const pageType = resolvedKind as PageKind;

        if (pageInfo.pageKey) {
          // Page has a pageKey — render via DynamicPageRenderer
          setRenderPage({
            tableName: modelCode || pageInfo.pageKey,
            pageKey: pageInfo.pageKey,
            pageType,
          });
        } else if (modelCode) {
          // Model-bound page without explicit pageKey
          setRenderPage({
            tableName: modelCode,
            pageType,
          });
        } else {
          setRedirecting(true);
          // No modelCode - might be a custom page, redirect to page-designer
          navigate(`/page-designer/${pageInfo.pid}`, { replace: true });
        }
      } catch (err) {
        console.error('Failed to fetch menu/page info:', err);
        setError(err instanceof Error ? err.message : 'Failed to load page configuration');
      } finally {
        setLoading(false);
      }
    };

    fetchMenuAndPageInfo();
  }, [location.pathname, token, navigate]);

  if (loading || redirecting) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-600 border-t-transparent"></div>
          <p className="text-gray-600">
            {redirecting ? 'Redirecting...' : 'Loading page configuration...'}
          </p>
        </div>
      </div>
    );
  }

  if (renderPage) {
    return (
      <DynamicPageRenderer
        tableName={renderPage.tableName}
        pageKey={renderPage.pageKey}
        pageType={renderPage.pageType}
        token={token}
      />
    );
  }

  if (error) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="max-w-md rounded-lg bg-white p-6 shadow-md">
          <div className="mb-4 flex items-center gap-3">
            <svg
              className="h-6 w-6 text-amber-500"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
              />
            </svg>
            <h2 className="text-lg font-semibold text-gray-900">Page Unavailable</h2>
          </div>
          <p className="mb-4 text-gray-600">{error}</p>
          <div className="flex gap-3">
            <button
              onClick={() => navigate(-1)}
              className="rounded-md bg-gray-100 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-200"
            >
              Go Back
            </button>
            <button
              onClick={() => navigate('/')}
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
            >
              Go Home
            </button>
          </div>
        </div>
      </div>
    );
  }

  // This shouldn't happen, but just in case
  return (
    <div className="flex min-h-screen items-center justify-center">
      <div className="text-center">
        <h1 className="mb-4 text-4xl font-bold text-gray-900">404</h1>
        <p className="mb-4 text-gray-600">Page Not Found</p>
        <button
          onClick={() => navigate('/')}
          className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          Go Home
        </button>
      </div>
    </div>
  );
}
