/**
 * PassthroughAction - direct backend mutation without local state management.
 * Used for scenarios like shopping cart increment/decrement, quick toggles, etc.
 *
 * Bypasses:
 * - Local form validation
 * - Optimistic state updates
 * - Local expression evaluation
 *
 * Flow: UI event → API call → refresh data source
 *
 * @since 3.7.0
 */

export interface PassthroughConfig {
  /** Command code to execute on backend */
  commandCode: string;
  /** Target model code */
  modelCode: string;
  /** Target record ID */
  recordId?: string;
  /** Payload fields to send */
  payload: Record<string, any>;
  /** Callback after successful execution */
  onSuccess?: (result: any) => void;
  /** Callback on failure */
  onError?: (error: Error) => void;
  /** Whether to show a confirmation dialog first */
  confirm?: { title: string; message: string };
  /** Data source IDs to refresh after success */
  refreshSources?: string[];
}

export type PassthroughExecutor = (config: PassthroughConfig) => Promise<any>;

/**
 * Create a passthrough executor that sends commands directly to the backend.
 */
export function createPassthroughExecutor(
  apiBase: string = '/api/meta/commands',
  fetchFn: typeof fetch = fetch,
): PassthroughExecutor {
  return async (config: PassthroughConfig) => {
    const { commandCode, modelCode, recordId, payload, onSuccess, onError } = config;

    const body = {
      commandCode,
      modelCode,
      targetRecordId: recordId,
      payload,
    };

    try {
      const response = await fetchFn(`${apiBase}/execute`, {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.message ?? `Command execution failed (${response.status})`);
      }

      const result = await response.json();
      onSuccess?.(result);
      return result;
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      onError?.(error);
      throw error;
    }
  };
}

/**
 * Common passthrough operations for typical use cases.
 */
export const PassthroughOps = {
  /** Increment a numeric field */
  increment(
    modelCode: string,
    recordId: string,
    field: string,
    amount: number = 1,
  ): PassthroughConfig {
    return {
      commandCode: `${modelCode}_increment`,
      modelCode,
      recordId,
      payload: { field, amount },
    };
  },

  /** Decrement a numeric field */
  decrement(
    modelCode: string,
    recordId: string,
    field: string,
    amount: number = 1,
  ): PassthroughConfig {
    return {
      commandCode: `${modelCode}_decrement`,
      modelCode,
      recordId,
      payload: { field, amount: -amount },
    };
  },

  /** Toggle a boolean field */
  toggle(modelCode: string, recordId: string, field: string): PassthroughConfig {
    return {
      commandCode: `${modelCode}_toggle`,
      modelCode,
      recordId,
      payload: { field },
    };
  },

  /** Quick status change */
  setStatus(modelCode: string, recordId: string, status: string): PassthroughConfig {
    return {
      commandCode: `${modelCode}_set_status`,
      modelCode,
      recordId,
      payload: { status },
    };
  },
};
