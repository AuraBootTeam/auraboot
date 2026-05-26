// web-admin/app/flow-designer-sdk/validation/useFlowValidation.ts
import { useCallback } from 'react';
import { useSmartText } from '~/utils/i18n';
import { useFlowStore } from '../store/useFlowStore';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { validateFlow } from './validateFlow';
import type { ValidationResult } from '../store/types';

/**
 * Imperative flow validation, shared by every save path (SDK toolbar + consumer
 * toolbars). {@link validate} runs {@link validateFlow} against the live store,
 * publishes the result so {@code FlowFieldAdapter} can surface field-level errors,
 * selects the first errored node so its panel is visible, and returns the result
 * so callers can gate save (P0-4).
 */
export function useFlowValidation() {
  const st = useSmartText();
  const setValidationResult = useFlowStore((s) => s.setValidationResult);
  const selectNode = useFlowStore((s) => s.selectNode);
  const validationResult = useFlowStore((s) => s.validationResult);

  const validate = useCallback((): ValidationResult => {
    const { nodes } = useFlowStore.getState();
    const result = validateFlow(nodes, (type) => nodeRegistry.get(type), {
      requiredMessage: st('$i18n:flow.validation.required') || 'This field is required',
    });
    setValidationResult(result.valid ? null : result);
    if (!result.valid && result.errors[0]?.nodeId) {
      selectNode(result.errors[0].nodeId);
    }
    return result;
  }, [st, setValidationResult, selectNode]);

  return { validate, validationResult };
}

export default useFlowValidation;
