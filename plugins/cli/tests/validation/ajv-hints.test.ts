import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import type { ErrorObject } from 'ajv';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ajvHint } from '../../src/validation/ajv-hints.js';
import { loadPlugin } from '../../src/utils/plugin-loader.js';
import { validateStructural } from '../../src/validation/structural.js';

const err = (o: Partial<ErrorObject>): ErrorObject =>
  ({ instancePath: '', schemaPath: '', keyword: '', params: {}, ...o }) as ErrorObject;

describe('ajvHint', () => {
  it('enum → lists the allowed values as expected + an imperative instruction', () => {
    const h = ajvHint(err({ keyword: 'enum', instancePath: '/0/dataType', params: { allowedValues: ['text', 'number', 'date'] } }));
    expect(h.expected).toBe('text, number, date');
    expect(h.agentInstruction).toMatch(/one of: text, number, date/);
    expect(h.agentInstruction).toContain('/0/dataType');
  });

  it('additionalProperties → instructs to remove the unknown property (typo hint)', () => {
    const h = ajvHint(err({ keyword: 'additionalProperties', instancePath: '/0', params: { additionalProperty: 'fieldType' } }));
    expect(h.agentInstruction).toMatch(/remove the unknown property "fieldType"/i);
  });

  it('required → names the missing property as expected + instruction to add it', () => {
    const h = ajvHint(err({ keyword: 'required', instancePath: '/0', params: { missingProperty: 'dataType' } }));
    expect(h.expected).toBe('dataType');
    expect(h.agentInstruction).toMatch(/add the required property "dataType"/i);
  });

  it('type → states the expected type', () => {
    const h = ajvHint(err({ keyword: 'type', instancePath: '/0/count', params: { type: 'string' } }));
    expect(h.expected).toBe('string');
    expect(h.agentInstruction).toMatch(/type string/);
  });

  it('returns empty for keywords it has no hint for', () => {
    expect(ajvHint(err({ keyword: 'pattern', instancePath: '/0/code' }))).toEqual({});
  });
});

describe('validateStructural surfaces agentInstruction (wiring)', () => {
  let dir: string;
  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), 'aura-hint-wiring-'));
    mkdirSync(join(dir, 'config'), { recursive: true });
    writeFileSync(
      join(dir, 'plugin.json'),
      JSON.stringify({ pluginId: 'com.h.demo', namespace: 'hx', version: '1.0.0', displayName: 'H', dslVersion: 1, pluginType: 'config', dependencies: [] }),
    );
    // A field with the legacy `fieldType` (missing required `dataType`) — the exact
    // mistake the old scaffold made; must now carry an actionable instruction.
    writeFileSync(join(dir, 'config', 'fields.json'), JSON.stringify([{ code: 'x', 'displayName:zh-CN': 'x', fieldType: 'text' }]));
  });
  afterEach(() => rmSync(dir, { recursive: true, force: true }));

  it('emits agentInstruction on a schema error (not just code + message)', () => {
    const result = validateStructural(loadPlugin(dir));
    const withInstr = result.messages.filter((m) => m.severity === 'error' && m.agentInstruction);
    expect(withInstr.length).toBeGreaterThan(0);
    expect(withInstr.some((m) => /dataType/.test(m.agentInstruction ?? ''))).toBe(true);
  });
});
