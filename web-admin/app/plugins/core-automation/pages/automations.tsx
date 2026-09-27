// web-admin/app/routes/automations.tsx
import { useLoaderData, type LoaderFunctionArgs } from 'react-router';
import { AutomationList } from '~/framework/smart/automation/components/AutomationList';
import { useSmartText } from '~/utils/i18n';
import {
  automationService,
  type Automation,
} from '~/framework/smart/automation/services/automationService';
import { workspacePageClassName } from '~/shared/layout/WorkspacePageLayout';

interface LoaderData {
  automations: Automation[];
  error?: string;
}

export const loader = async ({ request }: LoaderFunctionArgs): Promise<LoaderData> => {
  try {
    const automations = await automationService.list(undefined, request);
    return { automations };
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to load automations';
    return { automations: [], error: message };
  }
};

export default function AutomationsPage() {
  const st = useSmartText();
  const { automations, error } = useLoaderData<LoaderData>();

  return (
    <div className="min-h-screen bg-gray-50">
      <div className={workspacePageClassName('contentCompact')}>
        <h1 className="mb-6 text-2xl font-bold text-gray-900">
          {st('$i18n:automation.page.title') || 'Automation Management'}
        </h1>
        <AutomationList initialAutomations={automations} token={null} serverError={error} />
      </div>
    </div>
  );
}
