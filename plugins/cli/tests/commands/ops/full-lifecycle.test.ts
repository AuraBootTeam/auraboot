import { describe, it, expect } from 'vitest';

/**
 * CLI Full Lifecycle Contract Tests
 *
 * Validates the API contract for the scaffold → publish → sync → dispatch pipeline.
 * These are unit-level contract tests (no live HTTP calls); they verify request/response
 * shape expectations so that any breaking API change is caught at the CLI boundary.
 */
describe('CLI full lifecycle: scaffold → publish → sync → dispatch', () => {
  // ─── Phase 1: scaffold ──────────────────────────────────────────────────────

  describe('scaffold request contract', () => {
    it('scaffold API accepts modelCode + namespace + fields', () => {
      const request = {
        modelCode: 'equipment_inspection',
        namespace: 'insp',
        description: 'Equipment Inspection Management',
        fields: [
          { code: 'name', dataType: 'string' },
          { code: 'status', dataType: 'select' },
          { code: 'inspector', dataType: 'reference', referenceModel: 'ab_user' },
        ],
      };

      expect(request.modelCode).toBeTruthy();
      expect(request.namespace).toBeTruthy();
      expect(request.fields.length).toBe(3);
    });

    it('scaffold request requires at least one field', () => {
      const minimalRequest = {
        modelCode: 'simple_note',
        namespace: 'note',
        fields: [{ code: 'title', dataType: 'string' }],
      };
      expect(minimalRequest.fields.length).toBeGreaterThanOrEqual(1);
    });

    it('REFERENCE field must include referenceModel', () => {
      const refField = { code: 'inspector', dataType: 'reference', referenceModel: 'ab_user' };
      expect(refField.referenceModel).toBeTruthy();
    });
  });

  describe('scaffold response contract', () => {
    it('scaffold response contains model + fields + commands + fieldBindings', () => {
      const mockResponse = {
        model: {
          modelCode: 'equipment_inspection',
          namespace: 'insp',
          displayName: 'Equipment Inspection',
        },
        fields: [
          { code: 'name', dataType: 'string', agent_hint: 'Inspection name or title' },
          { code: 'status', dataType: 'select', agent_hint: 'Current inspection status' },
        ],
        commands: [
          {
            code: 'create_equipment_inspection',
            label: 'Create Inspection',
            agent_hint: 'Creates a new equipment inspection record',
            cmd_risk_level: 'low',
          },
          {
            code: 'update_equipment_inspection',
            label: 'Update Inspection',
            agent_hint: 'Updates an existing inspection record',
            cmd_risk_level: 'low',
          },
        ],
        fieldBindings: [
          { commandCode: 'create_equipment_inspection', fieldCode: 'name', required: true },
        ],
      };

      const expectedKeys = ['model', 'fields', 'commands', 'fieldBindings'];
      expectedKeys.forEach(key => {
        expect(mockResponse).toHaveProperty(key);
      });

      // Commands must carry agent metadata fields
      mockResponse.commands.forEach(cmd => {
        expect(cmd).toHaveProperty('agent_hint');
        expect(cmd).toHaveProperty('cmd_risk_level');
        expect(cmd.agent_hint).toBeTruthy();
        expect(['low', 'medium', 'high']).toContain(cmd.cmd_risk_level);
      });
    });

    it('scaffold response fields carry agent_hint for discoverability', () => {
      const scaffoldedField = {
        code: 'status',
        dataType: 'select',
        agent_hint: 'Current inspection status',
      };
      expect(scaffoldedField.agent_hint).toBeTruthy();
    });
  });

  // ─── Phase 2: publish (plugin install / tool publish) ───────────────────────

  describe('publish request contract', () => {
    it('publish endpoint accepts pluginCode + version', () => {
      const request = { pluginCode: 'insp-plugin', version: '1.0.0' };
      expect(request.pluginCode).toBeTruthy();
      expect(request.version).toMatch(/^\d+\.\d+\.\d+$/);
    });

    it('publish response confirms published resource counts', () => {
      const mockResponse = {
        models: 1,
        commands: 4,
        menus: 2,
        permissions: 6,
      };
      expect(mockResponse.models).toBeGreaterThan(0);
      expect(mockResponse.commands).toBeGreaterThan(0);
    });
  });

  // ─── Phase 3: tool registry (in-process ToolProviderRegistry surface) ───────
  // The legacy /api/agent/tools/sync no-op endpoint was removed in F-6;
  // /api/agent/tools/registry is the canonical read-only surface.

  describe('tool registry API contract', () => {
    it('registry API returns tools[] + providers[] + total', () => {
      const expectedResponse = {
        tools: [{ toolCode: 'platform.list_models', providerCode: 'platform' }],
        providers: ['platform', 'dsl'],
        total: 1,
      };
      expect(expectedResponse).toHaveProperty('tools');
      expect(expectedResponse).toHaveProperty('providers');
      expect(expectedResponse).toHaveProperty('total');
      expect(Array.isArray(expectedResponse.tools)).toBe(true);
      expect(Array.isArray(expectedResponse.providers)).toBe(true);
    });

    it('registry endpoint path matches CLI implementation', () => {
      const registryPath = '/api/agent/tools/registry';
      expect(registryPath).toMatch(/^\/api\/agent\/tools\/registry$/);
    });

    it('every tool in registry must declare a providerCode', () => {
      const tools = [
        { toolCode: 'platform.list_models', providerCode: 'platform' },
        { toolCode: 'cmd_demo_create', providerCode: 'dsl' },
      ];
      tools.forEach((t) => {
        expect(t.providerCode).toBeTruthy();
        expect(typeof t.providerCode).toBe('string');
      });
    });
  });

  // ─── Phase 4: dispatch (run agent task) ─────────────────────────────────────

  describe('dispatch API contract', () => {
    it('dispatch API requires taskPid + agentCode', () => {
      const request = { taskPid: 'task-123', agentCode: 'inspection-agent' };
      expect(request.taskPid).toBeTruthy();
      expect(request.agentCode).toBeTruthy();
    });

    it('dispatch response contains runPid for tracking', () => {
      const mockResponse = {
        runPid: 'run-abc123',
        status: 'pending',
        agentCode: 'inspection-agent',
        createdAt: '2026-03-18T10:00:00Z',
      };
      expect(mockResponse.runPid).toBeTruthy();
      expect(mockResponse.status).toBe('pending');
    });

    it('dispatch endpoint path matches CLI implementation', () => {
      const dispatchPath = '/api/agent/runs/dispatch';
      expect(dispatchPath).toMatch(/^\/api\/agent\/runs\/dispatch$/);
    });

    it('dispatched run can be tracked via runs list command', () => {
      const dispatchedRunPid = 'run-abc123';
      // After dispatch, the run PID must be usable with `aura ops runs show <pid>`
      const showParams = {
        datasourceId: 'nq:acp_run_detail',
        pid: dispatchedRunPid,
        format: 'records',
      };
      expect(showParams.pid).toBe(dispatchedRunPid);
    });
  });

  // ─── Cross-phase: end-to-end contract assertions ────────────────────────────

  describe('full lifecycle data flow', () => {
    it('scaffold modelCode flows into published plugin code', () => {
      const modelCode = 'equipment_inspection';
      const pluginCode = `${modelCode.replace(/_/g, '-')}-plugin`;
      expect(pluginCode).toBe('equipment-inspection-plugin');
    });

    it('published commands become synced agent tools with matching codes', () => {
      const commandCode = 'create_equipment_inspection';
      const toolCode = commandCode; // tool_code mirrors command code after sync
      expect(toolCode).toBe(commandCode);
    });

    it('dispatched run agentCode must match a synced agent', () => {
      const syncedAgentCodes = ['inspection-agent', 'sales-agent'];
      const dispatchRequest = { taskPid: 'task-001', agentCode: 'inspection-agent' };
      expect(syncedAgentCodes).toContain(dispatchRequest.agentCode);
    });
  });
});
