/**
 * Unit tests for the humanizeType helper.
 * Verifies that missing i18n label keys degrade to a readable label
 * instead of a raw kebab/snake code.
 */

import { describe, it, expect } from 'vitest';
import { humanizeType } from '../utils';

describe('humanizeType', () => {
  it('converts kebab-case to Title Case', () => {
    expect(humanizeType('trigger-record-create')).toBe('Trigger Record Create');
  });

  it('converts snake_case to Title Case', () => {
    expect(humanizeType('send_notification')).toBe('Send Notification');
  });

  it('converts single word to Capitalized', () => {
    expect(humanizeType('condition')).toBe('Condition');
  });

  it('converts mixed hyphens and underscores', () => {
    expect(humanizeType('on-record_update')).toBe('On Record Update');
  });

  it('handles consecutive separators', () => {
    expect(humanizeType('a--b__c')).toBe('A B C');
  });

  it('handles empty string', () => {
    expect(humanizeType('')).toBe('');
  });

  it('capitalizes word-boundary chars in camelCase (no hyphens/underscores)', () => {
    // \b word boundaries in JS also match before capital letters in camelCase,
    // so 'createRecord' → 'C' and 'R' are both at word boundaries → 'CreateRecord'
    expect(humanizeType('createRecord')).toBe('CreateRecord');
  });

  it('handles action types used in ExecutionLogDialog and DebugActionList', () => {
    expect(humanizeType('update_record')).toBe('Update Record');
    expect(humanizeType('create_record')).toBe('Create Record');
    expect(humanizeType('call_api')).toBe('Call Api');
    expect(humanizeType('send_webhook')).toBe('Send Webhook');
    expect(humanizeType('execute_command')).toBe('Execute Command');
  });

  it('handles trigger types used in ExecutionLogDialog', () => {
    expect(humanizeType('on_record_create')).toBe('On Record Create');
    expect(humanizeType('on_field_change')).toBe('On Field Change');
    expect(humanizeType('scheduled')).toBe('Scheduled');
    expect(humanizeType('webhook')).toBe('Webhook');
  });
});
