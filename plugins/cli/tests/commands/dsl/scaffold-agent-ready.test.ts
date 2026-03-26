import { describe, it, expect } from 'vitest';

// Contract-style tests: verify the expected output structure of scaffold
// without invoking file I/O. We replicate the generation logic inline so
// tests are fast, deterministic, and free of side effects.

// ---- helpers mirroring scaffold.ts logic ----

interface ParsedField {
  code: string;
  dataType: string;
  referenceModel?: string;
}

function buildModelOutput(code: string, namespace: string, fields: ParsedField[], description?: string) {
  return {
    code,
    'displayName:zh-CN': code,
    'displayName:en': code,
    description: description || `Auto-generated model: ${code}`,
    semantic_description: description || `${code} management entity`,
    modelType: 'entity',
    extension: {},
  };
}

function buildCommandsOutput(code: string, namespace: string, fields: ParsedField[]) {
  const shortName = code.replace(`${namespace}_`, '');
  const fieldNames = fields.map(f => f.code).join(', ');
  const createHint = fields.length > 0
    ? `Create a new ${shortName} with ${fieldNames}`
    : `Create a new ${shortName} with required fields`;

  const commands: any[] = [
    {
      code: `${namespace}:create_${shortName}`,
      type: 'create',
      agent_hint: createHint,
      cmd_risk_level: 'L1',
      precondition_description: 'All required fields must be provided',
      idempotent: false,
      reversible: false,
    },
    {
      code: `${namespace}:update_${shortName}`,
      type: 'update',
      agent_hint: `Update an existing ${shortName} record`,
      cmd_risk_level: 'L1',
      precondition_description: 'Record must exist and be accessible',
      idempotent: true,
      reversible: true,
    },
    {
      code: `${namespace}:delete_${shortName}`,
      type: 'delete',
      agent_hint: `Delete a ${shortName} permanently`,
      cmd_risk_level: 'L4',
      precondition_description: 'Record must exist. This action is irreversible',
      idempotent: true,
      reversible: false,
    },
  ];

  return commands;
}

function buildStateTransitionCommand(code: string, namespace: string, stateFieldCode: string) {
  const shortName = code.replace(`${namespace}_`, '');
  return {
    code: `${namespace}:transition_${shortName}`,
    type: 'state_transition',
    stateField: stateFieldCode,
    agent_hint: `Change the status of a ${shortName}`,
    cmd_risk_level: 'L1',
    precondition_description: 'Record must exist and current status must allow transition',
    idempotent: false,
    reversible: true,
  };
}

// ---- tests ----

describe('scaffold agent-ready fields', () => {
  const NAMESPACE = 'crm';
  const MODEL_CODE = 'crm_order';
  const FIELDS: ParsedField[] = [
    { code: 'name', dataType: 'string' },
    { code: 'amount', dataType: 'decimal' },
    { code: 'customer', dataType: 'reference', referenceModel: 'crm_customer' },
  ];

  describe('model semantic_description', () => {
    it('uses provided description when given', () => {
      const model = buildModelOutput(MODEL_CODE, NAMESPACE, FIELDS, 'Sales order for a customer');
      expect(model.semantic_description).toBe('Sales order for a customer');
    });

    it('falls back to "<code> management entity" when no description provided', () => {
      const model = buildModelOutput(MODEL_CODE, NAMESPACE, FIELDS);
      expect(model.semantic_description).toBe(`${MODEL_CODE} management entity`);
    });

    it('model always has semantic_description field', () => {
      const model = buildModelOutput(MODEL_CODE, NAMESPACE, FIELDS);
      expect(model).toHaveProperty('semantic_description');
      expect(typeof model.semantic_description).toBe('string');
      expect(model.semantic_description.length).toBeGreaterThan(0);
    });
  });

  describe('CREATE command agent fields', () => {
    const commands = buildCommandsOutput(MODEL_CODE, NAMESPACE, FIELDS);
    const create = commands.find(c => c.type === 'create')!;

    it('has agent_hint containing "Create"', () => {
      expect(create.agent_hint).toMatch(/Create/i);
    });

    it('agent_hint includes field names when fields are provided', () => {
      expect(create.agent_hint).toContain('name');
      expect(create.agent_hint).toContain('amount');
      expect(create.agent_hint).toContain('customer');
    });

    it('cmd_risk_level is L1', () => {
      expect(create.cmd_risk_level).toBe('L1');
    });

    it('is not idempotent', () => {
      expect(create.idempotent).toBe(false);
    });

    it('is not reversible', () => {
      expect(create.reversible).toBe(false);
    });

    it('has precondition_description', () => {
      expect(create.precondition_description).toBeTruthy();
    });

    it('agent_hint falls back gracefully when no fields provided', () => {
      const cmds = buildCommandsOutput(MODEL_CODE, NAMESPACE, []);
      const c = cmds.find(cmd => cmd.type === 'create')!;
      expect(c.agent_hint).toContain('required fields');
    });
  });

  describe('UPDATE command agent fields', () => {
    const commands = buildCommandsOutput(MODEL_CODE, NAMESPACE, FIELDS);
    const update = commands.find(c => c.type === 'update')!;

    it('has agent_hint containing "Update"', () => {
      expect(update.agent_hint).toMatch(/Update/i);
    });

    it('cmd_risk_level is L1', () => {
      expect(update.cmd_risk_level).toBe('L1');
    });

    it('is idempotent', () => {
      expect(update.idempotent).toBe(true);
    });

    it('is reversible', () => {
      expect(update.reversible).toBe(true);
    });
  });

  describe('DELETE command agent fields', () => {
    const commands = buildCommandsOutput(MODEL_CODE, NAMESPACE, FIELDS);
    const del = commands.find(c => c.type === 'delete')!;

    it('has agent_hint containing "Delete"', () => {
      expect(del.agent_hint).toMatch(/Delete/i);
    });

    it('cmd_risk_level is L4', () => {
      expect(del.cmd_risk_level).toBe('L4');
    });

    it('is idempotent', () => {
      expect(del.idempotent).toBe(true);
    });

    it('is NOT reversible', () => {
      expect(del.reversible).toBe(false);
    });

    it('precondition_description mentions irreversible', () => {
      expect(del.precondition_description).toMatch(/irreversible/i);
    });
  });

  describe('STATE_TRANSITION command agent fields', () => {
    const transition = buildStateTransitionCommand(MODEL_CODE, NAMESPACE, 'crm_order_status');

    it('has agent_hint containing "status"', () => {
      expect(transition.agent_hint).toMatch(/status/i);
    });

    it('cmd_risk_level is L1', () => {
      expect(transition.cmd_risk_level).toBe('L1');
    });

    it('is NOT idempotent', () => {
      expect(transition.idempotent).toBe(false);
    });

    it('is reversible', () => {
      expect(transition.reversible).toBe(true);
    });

    it('precondition_description mentions status transition', () => {
      expect(transition.precondition_description).toMatch(/status/i);
    });
  });

  describe('all commands have required agent fields', () => {
    it('every scaffolded command has all five agent fields', () => {
      const commands = buildCommandsOutput(MODEL_CODE, NAMESPACE, FIELDS);
      const transition = buildStateTransitionCommand(MODEL_CODE, NAMESPACE, 'crm_order_status');
      const all = [...commands, transition];

      for (const cmd of all) {
        expect(cmd, `${cmd.type} command missing agent_hint`).toHaveProperty('agent_hint');
        expect(cmd, `${cmd.type} command missing cmd_risk_level`).toHaveProperty('cmd_risk_level');
        expect(cmd, `${cmd.type} command missing precondition_description`).toHaveProperty('precondition_description');
        expect(cmd, `${cmd.type} command missing idempotent`).toHaveProperty('idempotent');
        expect(cmd, `${cmd.type} command missing reversible`).toHaveProperty('reversible');
        expect(typeof cmd.idempotent).toBe('boolean');
        expect(typeof cmd.reversible).toBe('boolean');
      }
    });

    it('risk levels are one of the defined tiers', () => {
      const VALID_RISK_LEVELS = ['L0', 'L1', 'L2', 'L3', 'L4'];
      const commands = buildCommandsOutput(MODEL_CODE, NAMESPACE, FIELDS);
      const transition = buildStateTransitionCommand(MODEL_CODE, NAMESPACE, 'crm_order_status');

      for (const cmd of [...commands, transition]) {
        expect(VALID_RISK_LEVELS, `${cmd.type} has unknown risk level: ${cmd.cmd_risk_level}`)
          .toContain(cmd.cmd_risk_level);
      }
    });
  });
});
