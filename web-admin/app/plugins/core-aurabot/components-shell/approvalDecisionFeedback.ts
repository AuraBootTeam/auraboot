/** Safe display copy for approval transport errors; raw diagnostics stay in server logs. */
export function approvalDecisionFeedback(error: string): { key: string; zh: string; en: string } {
  if (error === 'TOOL_ACCESS_DENIED') {
    return { key: 'ai.approval.toolAccessDenied', zh: '当前操作未获授权，未执行。请联系管理员授权后再试；确认审批不会补授权限。', en: 'This action was not authorized and was not executed. Contact an administrator for access; approval does not grant permission.' };
  }
  if (error === 'TOOL_EXECUTION_FAILED') {
    return { key: 'ai.approval.toolExecutionFailed', zh: '操作未完成，请查看结果或联系管理员。', en: 'The action did not complete. Check the result or contact an administrator.' };
  }
  if (error === 'Insufficient permission to decide this approval') {
    return { key: 'ai.approval.unauthorized', zh: '你无权处理这项审批，请联系指定审批人。', en: 'You cannot decide this approval. Contact the designated approver.' };
  }
  if (/already processed or expired|no longer pending \(status=expired\)/i.test(error)) {
    return { key: 'ai.approval.expired', zh: '该审批已过期或已处理，请刷新后重新查看。', en: 'This approval has expired or was already processed. Refresh to check its status.' };
  }
  if (/no longer pending|expired or already consumed/i.test(error)) {
    return { key: 'ai.approval.alreadyProcessed', zh: '该审批已处理，请刷新后重新查看。', en: 'This approval has already been processed. Refresh to check its status.' };
  }
  return { key: 'ai.approval.failed', zh: '本次审批未完成，请刷新后检查状态或联系管理员。', en: 'The approval did not complete. Refresh to check its status or contact an administrator.' };
}
