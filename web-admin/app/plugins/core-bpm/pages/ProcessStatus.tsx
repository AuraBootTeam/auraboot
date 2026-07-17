/**
 * Process Status page — read-only BPMN diagram with runtime node status.
 *
 * URL params:
 *   ?processInstanceId=<id>                   — query by instance ID
 *   ?businessKey=<key>&processKey=<key>       — query by business key
 *
 * Migrated from app/routes/bpm/process-status.tsx (M3 core-bpm extraction).
 */
import { useSearchParams } from 'react-router'
import { ReactFlowProvider } from '@xyflow/react'
import { ProcessStatusViewer } from '~/plugins/core-designer/components/bpmn-designer/components/ProcessStatusViewer'

export default function ProcessStatusPage() {
  const [searchParams] = useSearchParams()

  const processInstanceId = searchParams.get('processInstanceId') ?? undefined
  const processKey = searchParams.get('processKey') ?? undefined
  const businessKey = searchParams.get('businessKey') ?? undefined

  if (!processInstanceId && !businessKey) {
    return (
      <div className="bg-bg flex h-screen items-center justify-center">
        <div className="text-text-2 text-center">
          <p className="mb-2 text-lg font-medium">缺少流程查询参数</p>
          <p className="text-sm">
            请在查询参数中提供 <code>processInstanceId</code> 或 <code>businessKey</code>
            （可选 <code>processKey</code>）。
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="bg-bg h-screen">
      <ReactFlowProvider>
        <ProcessStatusViewer
          processInstanceId={processInstanceId}
          processKey={processKey}
          businessKey={businessKey}
        />
      </ReactFlowProvider>
    </div>
  )
}
