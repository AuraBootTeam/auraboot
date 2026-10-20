import { describe, expect, it } from 'vitest';
import { commandParameterKeys } from '../ActionModelParameters';

describe('command parameter metadata', () => {
  it('uses declared input properties and the existing input whitelist', () => {
    expect(commandParameterKeys({ modelCode: 'project',
      inputSchema: JSON.stringify({ properties: { acceptancePid: { type: 'string' } } }),
      executionConfig: JSON.stringify({ inputFields: ['acceptancePid', 'note'] }),
    })).toEqual(['acceptancePid', 'note']);
  });

  it('does not expose undeclared model fields as writable command parameters', () => {
    expect(commandParameterKeys({ modelCode: 'project', executionConfig: '{"inputFields":[]}' })).toEqual([]);
  });

  it('rejects invalid metadata instead of replacing it with an empty successful editor', () => {
    expect(() => commandParameterKeys({ modelCode: 'project', inputSchema: '{broken' })).toThrow();
  });
});
