/**
 * Runtime content for block-composed pages that do not need a specialised
 * CRUD shell (dashboard, composite, page, kanban and page_layout).
 */

import { useCallback } from 'react';
import type { PageContentProps } from '@auraboot/runtime-kernel';
import { useToastContext } from '~/contexts/ToastContext';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import { SchemaRendererWithContainer } from '~/framework/meta/rendering/SchemaRenderer';
import { usePageRuntime } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';

export function ComposedPageContent({ schema }: PageContentProps) {
  const { showToast: showContextToast } = useToastContext();
  const showToast = useCallback(
    (message: string, level: 'success' | 'error' | 'info' | 'warning' = 'info') => {
      showContextToast(message, level);
    },
    [showContextToast],
  );
  const { runtime } = usePageRuntime(schema, { showToast });

  if (!runtime) {
    return <LoadingSpinner />;
  }

  return <SchemaRendererWithContainer schema={schema} runtime={runtime} />;
}

export default ComposedPageContent;
