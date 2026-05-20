import { get, post } from '~/shared/services/http-client';
import type { Result } from '~/shared/services/http-client';
import type { CommandDefinitionDTO, BindingRuleDTO } from '~/plugins/core-designer/components/studio/workbench/panels/actions/types';

/**
 * Command Action Service.
 * Provides API calls to backend Command Definition and BindingRule endpoints.
 *
 * @since 3.3.0
 */
export class CommandActionService {
  /**
   * List commands for a model.
   * GET /api/meta/commands?modelCode={modelCode}
   */
  async listByModelCode(modelCode: string): Promise<CommandDefinitionDTO[]> {
    const result = await get<CommandDefinitionDTO[]>('/api/meta/commands', { modelCode });
    return result?.data ?? [];
  }

  /**
   * Get command by PID.
   * GET /api/meta/commands/{pid}
   */
  async getByPid(pid: string): Promise<CommandDefinitionDTO | null> {
    const result = await get<CommandDefinitionDTO>(`/api/meta/commands/${pid}`);
    return result?.data ?? null;
  }

  /**
   * Get command by code.
   * GET /api/meta/commands/by-code/{code}
   */
  async getByCode(code: string): Promise<CommandDefinitionDTO | null> {
    const result = await get<CommandDefinitionDTO>(`/api/meta/commands/by-code/${code}`);
    return result?.data ?? null;
  }

  /**
   * Get binding rules for a command.
   * GET /api/meta/commands/{pid}/binding-rules
   */
  async getBindingRules(commandPid: string): Promise<BindingRuleDTO[]> {
    const result = await get<BindingRuleDTO[]>(`/api/meta/commands/${commandPid}/binding-rules`);
    return result?.data ?? [];
  }

  /**
   * Execute a command.
   * POST /api/meta/commands/execute/{commandCode}
   */
  async execute(
    commandCode: string,
    payload: Record<string, any>,
    options?: {
      clientRequestId?: string;
      targetRecordPid?: string;
      targetRecordId?: string;
      operationType?: 'create' | 'update' | 'delete';
      auditContext?: Record<string, unknown>;
    },
  ): Promise<CommandExecuteResult> {
    const normalizedOptions = options
      ? {
          ...options,
          targetRecordId: options.targetRecordId ?? options.targetRecordPid,
        }
      : undefined;
    const result = await post<CommandExecuteResult>(`/api/meta/commands/execute/${commandCode}`, {
      payload,
      ...normalizedOptions,
    });
    if (!isSuccessCode(result?.code) || !result?.data) {
      throw createCommandServiceError(result, 'Command execution failed');
    }
    return result.data;
  }
}

function isSuccessCode(code: unknown): boolean {
  return code === '0' || code === 0;
}

function createCommandServiceError(
  result: Result<CommandExecuteResult> | undefined,
  fallback: string,
): Error {
  const error = new Error(result?.message || result?.desc || fallback);
  Object.assign(error, {
    code: result?.code,
    context: result?.context ?? null,
  });
  return error;
}

export interface CommandExecuteResult {
  commandCode?: string;
  phaseReached?: string;
  data?: Record<string, any>;
  executionTimeMs?: number;
  idempotentReplay?: boolean;
}

export const commandActionService = new CommandActionService();
