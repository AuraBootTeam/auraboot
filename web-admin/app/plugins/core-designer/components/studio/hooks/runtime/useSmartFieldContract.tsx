import { useConditionalRender, useExpressionValue } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartText, type SmartText } from '~/utils/i18n';

export interface SmartFieldExpressions {
  visible?: any;
  disabled?: any;
  required?: any;
  helpText?: any;
}

export interface SmartFieldContractInput {
  label?: SmartText;
  placeholder?: SmartText;
  helpText?: SmartText;
  required?: any;
  disabled?: any;
  visible?: any;
  expressions?: SmartFieldExpressions;
  context?: Record<string, any>;
}

export function useSmartFieldContract({
  label,
  placeholder,
  helpText,
  required,
  disabled,
  visible,
  expressions,
  context,
}: SmartFieldContractInput) {
  const st = useSmartText();
  const resolvedVisible = useConditionalRender(visible ?? expressions?.visible ?? true, context);
  const resolvedDisabled = useExpressionValue(disabled ?? expressions?.disabled, context);
  const resolvedRequired = useExpressionValue(required ?? expressions?.required, context);
  const resolvedHelpText = useExpressionValue(helpText ?? expressions?.helpText, context);

  return {
    labelText: label !== undefined ? st(label) : undefined,
    placeholderText: placeholder !== undefined ? st(placeholder) : undefined,
    helpText:
      resolvedHelpText !== undefined && resolvedHelpText !== null && resolvedHelpText !== ''
        ? st(resolvedHelpText as SmartText)
        : undefined,
    required: resolvedRequired,
    disabled: resolvedDisabled,
    visible: resolvedVisible,
  };
}
