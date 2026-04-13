/**
 * Automation Editor Route
 *
 * Lazy-loads the AutomationEditor + flow-designer-sdk to reduce initial bundle.
 * The loader runs server-side and stays in this file.
 */

import React, { Suspense } from 'react';
import type { LoaderFunctionArgs } from 'react-router';
import { automationService } from '~/framework/smart/automation/services/automationService';
import type { Automation } from '~/framework/smart/automation/services/automationService';
import { getTokenFromRequest } from '~/services/session';
import { RouteLoadingFallback } from '~/components/RouteLoadingFallback';

const AutomationEditPageImpl = React.lazy(
  () => import('~/framework/smart/automation/components/AutomationEditPageImpl'),
);

interface LoaderData {
  automation: Automation | null;
  token: string | null;
  isNew: boolean;
  error?: string;
}

export const loader = async ({ request, params }: LoaderFunctionArgs): Promise<LoaderData> => {
  const id = params.id!;
  const isNew = id === 'new';

  try {
    const token = await getTokenFromRequest(request);
    if (isNew) {
      return { automation: null, token, isNew };
    }
    const automation = await automationService.get(id, request);
    return { automation, token, isNew };
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to load automation';
    return { automation: null, token: null, isNew, error: message };
  }
};

export default function AutomationEditPage() {
  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <AutomationEditPageImpl />
    </Suspense>
  );
}
