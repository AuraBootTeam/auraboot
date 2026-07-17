/**
 * Mappings from automation raw type codes (snake_case) to i18n keys.
 *
 * These mappings are used by ExecutionLogDialog and AutomationDebugger to
 * avoid rendering raw codes like `send_notification` or `on_record_create`
 * to end users.
 */

/** Maps action type raw codes to their $i18n: keys. */
export const ACTION_TYPE_I18N_KEYS: Record<string, string> = {
  update_record: '$i18n:automation.action.updateRecord',
  create_record: '$i18n:automation.action.createRecord',
  send_notification: '$i18n:automation.action.sendNotification',
  execute_command: '$i18n:automation.action.executeCommand',
  call_api: '$i18n:automation.action.callApi',
  send_webhook: '$i18n:automation.action.sendWebhook',
  start_process: '$i18n:automation.action.startProcess',
  send_sms: '$i18n:automation.action.sendSms',
  send_im: '$i18n:automation.action.sendIm',
  create_task: '$i18n:automation.action.createTask',
  cc_task: '$i18n:automation.action.ccTask',
  add_comment: '$i18n:automation.action.addComment',
  patch_record: '$i18n:automation.action.patchRecord',
  write_audit: '$i18n:automation.action.writeAudit',
  llm_call: '$i18n:automation.action.llmCall',
  // Control node types that may appear as action types in execution results
  condition: '$i18n:automation.control.condition',
  delay: '$i18n:automation.control.delay',
  loop: '$i18n:automation.control.loop',
};

/** Maps trigger type raw codes to their $i18n: keys. */
export const TRIGGER_TYPE_I18N_KEYS: Record<string, string> = {
  on_record_create: '$i18n:automation.trigger.recordCreate',
  on_record_update: '$i18n:automation.trigger.recordUpdate',
  on_field_change: '$i18n:automation.trigger.fieldChange',
  on_state_change: '$i18n:automation.trigger.stateChange',
  scheduled: '$i18n:automation.trigger.scheduled',
  webhook: '$i18n:automation.trigger.webhook',
  on_bpm_event: '$i18n:automation.trigger.bpmEvent',
  on_inactivity: '$i18n:automation.trigger.inactivity',
};
