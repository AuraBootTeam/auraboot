// web-admin/app/routes/automations.tsx
import type { LoaderFunctionArgs } from 'react-router';
import { useLoaderData } from 'react-router';
import { AutomationList } from '~/framework/smart/automation/components/AutomationList';
import { useSmartText } from '~/utils/i18n';
import { automationService } from '~/framework/smart/automation/services/automationService';
import type { Automation } from '~/framework/smart/automation/services/automationService';
import { getTokenFromRequest } from '~/services/session';

interface LoaderData {
  automations: Automation[];
  token: string | null;
  error?: string;
}

export const loader = async ({ request }: LoaderFunctionArgs): Promise<LoaderData> => {
  try {
    const token = await getTokenFromRequest(request);
    const automations = await automationService.list(undefined, request);
    return { automations, token };
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to load automations';
    return { automations: [], token: null, error: message };
  }
};

export default function AutomationsPage() {
  const st = useSmartText();
  const { automations, token, error } = useLoaderData<LoaderData>();

  return (
    <div className="min-h-screen bg-gray-50 py-8">
      <div className="mx-auto max-w-6xl px-4">
        <h1 className="mb-6 text-2xl font-bold text-gray-900">
          {st('$i18n:automation.page.title') || 'Automation Management'}
        </h1>
        <AutomationList initialAutomations={automations} token={token} serverError={error} />
      </div>
    </div>
  );
}
