/**
 * Process Status Route
 *
 * Read-only BPMN diagram with runtime node status highlighting.
 *
 * URL params:
 *   ?processInstanceId=<id>                   — query by instance ID
 *   ?businessKey=<key>&processKey=<key>       — query by business key
 */

import { useSearchParams } from 'react-router';
import { ReactFlowProvider } from '@xyflow/react';
import { ProcessStatusViewer } from '~/bpmn-designer/components/ProcessStatusViewer';

export default function ProcessStatusPage() {
  const [searchParams] = useSearchParams();

  const processInstanceId = searchParams.get('processInstanceId') ?? undefined;
  const processKey = searchParams.get('processKey') ?? undefined;
  const businessKey = searchParams.get('businessKey') ?? undefined;

  if (!processInstanceId && !businessKey) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="text-center text-gray-500">
          <p className="mb-2 text-lg font-medium">Missing parameters</p>
          <p className="text-sm">
            Provide <code>processInstanceId</code> or <code>businessKey</code> (+ optional{' '}
            <code>processKey</code>) as query parameters.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="h-screen bg-gray-50">
      <ReactFlowProvider>
        <ProcessStatusViewer
          processInstanceId={processInstanceId}
          processKey={processKey}
          businessKey={businessKey}
        />
      </ReactFlowProvider>
    </div>
  );
}
