export type Severity = 'error' | 'warning' | 'info';

export interface ValidationMessage {
  code: string;
  category: 'structural' | 'semantic' | 'governance';
  severity: Severity;
  message: string;
  path?: string;
  suggestion?: string;
  /** The value the validator expected (e.g. the correct property name). */
  expected?: string;
  /** Imperative fix an AI agent can apply directly, e.g. "Rename x to y". */
  agentInstruction?: string;
}

export interface ValidationResult {
  valid: boolean;
  messages: ValidationMessage[];
  errorCount: number;
  warningCount: number;
  infoCount: number;
}

export function createResult(): ValidationResult {
  return { valid: true, messages: [], errorCount: 0, warningCount: 0, infoCount: 0 };
}

export function addMessage(result: ValidationResult, msg: ValidationMessage): void {
  result.messages.push(msg);
  if (msg.severity === 'error') {
    result.errorCount++;
    result.valid = false;
  } else if (msg.severity === 'warning') {
    result.warningCount++;
  } else {
    result.infoCount++;
  }
}

export function mergeResults(target: ValidationResult, source: ValidationResult): void {
  for (const msg of source.messages) {
    addMessage(target, msg);
  }
}
