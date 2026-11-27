/**
 * Flow Designer SDK — shared utility functions.
 */

/**
 * Converts a kebab-case or snake_case node type string into a human-readable
 * title-case label.
 *
 * Used as the last-resort fallback when an i18n label key is missing for a
 * node type, so users see "Trigger Record Create" instead of the raw code
 * "trigger-record-create" or "trigger_record_create".
 *
 * @example
 * humanizeType('trigger-record-create') // → 'Trigger Record Create'
 * humanizeType('send_notification')     // → 'Send Notification'
 * humanizeType('condition')             // → 'Condition'
 */
export function humanizeType(type: string): string {
  if (!type) return '';
  return type
    .replace(/[-_]+/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
