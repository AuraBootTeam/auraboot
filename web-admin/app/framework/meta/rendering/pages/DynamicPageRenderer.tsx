/**
 * DynamicPageRenderer — Profile-aware unified entry point for dynamic pages
 *
 * Responsibilities:
 * 1. Load schema via useSchemaLoader
 * 2. Resolve DSL Profile (from schema.profile or props)
 * 3. Show profile-specific skeleton during loading
 * 4. Delegate to profile's page renderer
 *
 * Usage:
 *   <DynamicPageRenderer tableName="device" pageType="list" />
 *   <DynamicPageRenderer tableName="order" pageType="form" />
 *   <DynamicPageRenderer tableName="order" pageType="form" recordId="123" />  // edit mode
 */

import React, { Suspense } from 'react';
import { useSchemaLoader } from '~/framework/meta/hooks/useSchemaLoader';
import { profileRegistry } from '~/framework/meta/profiles/ProfileRegistry';
import { ProfileProvider } from '~/framework/meta/profiles/ProfileContext';
import { ErrorAlert } from '~/components/ErrorAlert';
import { LoadingSpinner } from '~/components/LoadingSpinner';


// Ensure profiles are registered
import '~/framework/meta/profiles/admin';
import '~/framework/meta/profiles/report';

export interface DynamicPageRendererProps {
  /** Model table name (e.g., "device", "pe-order") */
  tableName: string;
  /** Page kind — must match ab_page_schema.kind values */
  pageType: 'list' | 'form' | 'detail' | 'dashboard' | 'kanban' | 'composite';
  /** Profile name override (default resolves from schema.profile or "admin") */
  profileName?: string;
  /** Record ID for edit/detail modes */
  recordId?: string;
  /** Auth token */
  token?: string | null;
  /** Custom page key (bypasses tableName + pageType generation) */
  pageKey?: string;
}

export function DynamicPageRenderer({
  tableName,
  pageType,
  profileName = 'admin',
  recordId,
  token,
  pageKey,
}: DynamicPageRendererProps) {
  // 1. Validate pageType matches DB kind values
  const VALID_KINDS = ['list', 'form', 'detail', 'dashboard', 'kanban', 'composite'] as const;
  if (!VALID_KINDS.includes(pageType)) {
    return (
      <ErrorAlert
        error={`Invalid pageType "${pageType}" for "${tableName}". Expected one of: ${VALID_KINDS.join(', ')}. If you see "new" or "edit", update the route to pass "form" instead.`}
      />
    );
  }

  // 2. Load schema
  const { schema, loading, error } = useSchemaLoader(
    pageKey
      ? { pageKey, token: token || undefined }
      : { tableName, type: pageType, token: token || undefined },
  );

  // 2. Resolve profile (schema.profile > props.profileName > "admin")
  let profile;
  try {
    profile = profileRegistry.resolve(schema, profileName);
  } catch {
    // Fallback to admin if profile resolution fails
    profile = profileRegistry.get('admin');
  }

  // 3. Loading state — show profile skeleton
  if (loading) {
    if (profile?.skeletons) {
      const Skeleton = profile.skeletons.get(pageType);
      if (Skeleton) {
        return <Skeleton />;
      }
    }
    return <LoadingSpinner />;
  }

  // 4. Error state
  if (error) {
    return <ErrorAlert error={error.message} />;
  }

  // 5. No schema
  if (!schema) {
    return <ErrorAlert error={`No schema found for ${tableName} (${pageType})`} />;
  }

  // 6. Resolve page renderer from profile
  if (profile) {
    const PageContent = profile.pageRenderers.get(schema.kind);
    if (PageContent) {
      // Resolve skeleton for loading state during lazy chunk download
      const Skeleton = profile.skeletons?.get(schema.kind);
      const fallback = Skeleton ? <Skeleton /> : <LoadingSpinner />;

      return (
        <ProfileProvider value={profile}>
          <div data-testid={`dynamic-page-${pageType}`}>
            <Suspense fallback={fallback}>
              <PageContent
                schema={schema}
                tableName={tableName}
                recordId={recordId}
                token={token}
              />
            </Suspense>
          </div>
        </ProfileProvider>
      );
    }
  }

  // 7. Fallback — no profile page renderer found
  // This happens when page renderers haven't been extracted yet.
  // The existing route components handle their own rendering.
  // This fallback renders a warning for debugging.
  if (process.env.NODE_ENV === 'development') {
    console.warn(
      `[DynamicPageRenderer] No page renderer for kind="${schema.kind}" in profile="${profile?.name}". ` +
        `Using route-level rendering. Consider extracting page content to profile.pageRenderers.`,
    );
  }

  return (
    <ProfileProvider value={profile!}>
      <ErrorAlert
        error={`Page renderer not available for kind "${schema.kind}" in profile "${profile?.name}". This page should be rendered via the route component.`}
      />
    </ProfileProvider>
  );
}

export default DynamicPageRenderer;
