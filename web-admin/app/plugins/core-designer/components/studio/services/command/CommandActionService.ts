import { get, post } from '~/shared/services/http-client';
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
    if (!result?.data) {
      throw new Error('Command execution failed: no response');
    }
    return result.data;
  }
}

export interface CommandExecuteResult {
  commandCode?: string;
  phaseReached?: string;
  data?: Record<string, any>;
  executionTimeMs?: number;
  idempotentReplay?: boolean;
}

export const commandActionService = new CommandActionService();
