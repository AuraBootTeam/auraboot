/**
 * Canonical server mutation routes used by the agent-native CLI/MCP layer.
 *
 * Keep route construction here so MCP write tools cannot silently grow their
 * own transport paths. Business command execution goes through the command
 * pipeline endpoint; control-plane authoring uses the platform's canonical
 * model/page/command/import controllers.
 */
export const WRITE_ROUTES = {
  executeCommand: (commandCode: string): string =>
    `/api/meta/commands/execute/${commandCode}`,
  createModel: '/api/meta/models',
  createPageSchema: '/api/pages',
  createCommand: '/api/meta/commands',
  createBindingRule: (commandPid: string): string =>
    `/api/meta/commands/${encodeURIComponent(commandPid)}/binding-rules`,
  deleteCommand: (commandPid: string): string =>
    `/api/meta/commands/${encodeURIComponent(commandPid)}`,
  importPlugin: '/api/plugins/import/execute-direct',
  rollbackImport: (importId: string): string =>
    `/api/plugins/import/${encodeURIComponent(importId)}/rollback`,
} as const;
