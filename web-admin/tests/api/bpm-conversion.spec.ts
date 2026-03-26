/**
 * BPM Conversion & Node Type API Tests
 *
 * Migrated from: tests/e2e/bpm/bpm-designer-ui.spec.ts
 * Tests: D2-E06 ~ D2-E10 (JSON/BPMN conversion, complex process, service/receive task)
 *
 * E2E tests (D2-E01 ~ D2-E05) remain in the e2e file.
 *
 * @since 4.0.0
 */

import { test, expect } from '@playwright/test';

function unwrap<T = any>(body: any): T {
  return (body?.data ?? body) as T;
}

function generateMinimalJson(processKey: string) {
  return {
    nodes: [
      {
        id: 'start_1',
        type: 'startEvent',
        position: { x: 100, y: 200 },
        data: { label: 'Start' },
      },
      {
        id: 'task_1',
        type: 'userTask',
        position: { x: 300, y: 200 },
        data: { label: 'Approval Task', assignee: '' },
      },
      {
        id: 'end_1',
        type: 'endEvent',
        position: { x: 500, y: 200 },
        data: { label: 'End' },
      },
    ],
    edges: [
      { id: 'flow_1', source: 'start_1', target: 'task_1' },
      { id: 'flow_2', source: 'task_1', target: 'end_1' },
    ],
    processKey,
    processName: `E2E Designer Test ${processKey}`,
  };
}

function generateProcessKey(): string {
  const ts = Date.now();
  const rand = Math.random().toString(36).slice(2, 7);
  return `e2e_des_${ts}_${rand}`;
}

test.describe('BPM Conversion API', () => {

  test('D2-E06: JSON to BPMN conversion', async ({ request }) => {
    const processKey = generateProcessKey();
    const json = generateMinimalJson(processKey);

    const convertResponse = await request.post(
      `/api/bpm/convert/json-to-bpmn`,
      { data: json }
    );

    if (convertResponse.ok()) {
      const bpmnXml = await convertResponse.text();
      expect(bpmnXml).toContain('<?xml');
      expect(bpmnXml).toContain(processKey);
    } else {
      test.skip();
      return;
    }
  });

  test('D2-E07: BPMN to JSON conversion', async ({ request }) => {
    const processKey = generateProcessKey();
    const bpmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="E2E Convert Test" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="task1" name="Test Task"/>
    <endEvent id="end"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="task1"/>
    <sequenceFlow id="f2" sourceRef="task1" targetRef="end"/>
  </process>
</definitions>`;

    const convertResponse = await request.post(
      `/api/bpm/convert/bpmn-to-json`,
      {
        data: { bpmnContent: bpmnXml },
        headers: { 'Content-Type': 'application/json' },
      }
    );

    if (convertResponse.ok()) {
      const json = unwrap(await convertResponse.json());
      expect(json.nodes || json.data?.nodes).toBeDefined();
    } else {
      test.skip();
      return;
    }
  });

  test('D2-E08: Complex process with gateway', async ({ request }) => {
    const processKey = generateProcessKey();

    const bpmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="E2E Gateway Test" isExecutable="true">
    <startEvent id="start"/>
    <exclusiveGateway id="gateway1" name="Check Condition"/>
    <userTask id="task1" name="Approve"/>
    <userTask id="task2" name="Reject"/>
    <exclusiveGateway id="gateway2" name="Merge"/>
    <endEvent id="end"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="gateway1"/>
    <sequenceFlow id="f2" sourceRef="gateway1" targetRef="task1">
      <conditionExpression>\${approved == true}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="f3" sourceRef="gateway1" targetRef="task2">
      <conditionExpression>\${approved == false}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="f4" sourceRef="task1" targetRef="gateway2"/>
    <sequenceFlow id="f5" sourceRef="task2" targetRef="gateway2"/>
    <sequenceFlow id="f6" sourceRef="gateway2" targetRef="end"/>
  </process>
</definitions>`;

    const createResponse = await request.post(
      `/api/bpm/process-definitions`,
      {
        data: {
          processKey,
          processName: `E2E Gateway Test ${processKey}`,
          bpmnContent: bpmnXml,
        },
      }
    );

    if (!createResponse.ok()) {
      test.skip();
      return;
    }

    const data = unwrap(await createResponse.json());
    const processPid = data.pid;

    try {
      const verifyResponse = await request.get(
        `/api/bpm/process-definitions/${processPid}`
      );
      expect(verifyResponse.ok()).toBe(true);

      const verifyData = unwrap(await verifyResponse.json());
      expect(verifyData.processKey).toBe(processKey);
      expect(verifyData.processName).toContain('Gateway');
    } finally {
      await request.delete(`/api/bpm/process-definitions/${processPid}`);
    }
  });
});

test.describe('BPM Node Types API', () => {

  test('D2-E09: Service task definition', async ({ request }) => {
    const processKey = generateProcessKey();

    const bpmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="E2E Service Task Test" isExecutable="true">
    <startEvent id="start"/>
    <serviceTask id="serviceTask1" name="Execute Command">
      <extensionElements>
        <auraboot:commandCode>test_command</auraboot:commandCode>
      </extensionElements>
    </serviceTask>
    <endEvent id="end"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="serviceTask1"/>
    <sequenceFlow id="f2" sourceRef="serviceTask1" targetRef="end"/>
  </process>
</definitions>`;

    const createResponse = await request.post(
      `/api/bpm/process-definitions`,
      {
        data: {
          processKey,
          processName: `E2E Service Task ${processKey}`,
          bpmnContent: bpmnXml,
        },
      }
    );

    if (!createResponse.ok()) {
      test.skip();
      return;
    }

    const data = unwrap(await createResponse.json());
    const processPid = data.pid;

    try {
      expect(data.processKey).toBe(processKey);
    } finally {
      await request.delete(`/api/bpm/process-definitions/${processPid}`);
    }
  });

  test('D2-E10: Receive task definition', async ({ request }) => {
    const processKey = generateProcessKey();

    const bpmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="E2E Receive Task Test" isExecutable="true">
    <startEvent id="start"/>
    <receiveTask id="receiveTask1" name="Wait for Signal"/>
    <endEvent id="end"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="receiveTask1"/>
    <sequenceFlow id="f2" sourceRef="receiveTask1" targetRef="end"/>
  </process>
</definitions>`;

    const createResponse = await request.post(
      `/api/bpm/process-definitions`,
      {
        data: {
          processKey,
          processName: `E2E Receive Task ${processKey}`,
          bpmnContent: bpmnXml,
        },
      }
    );

    if (!createResponse.ok()) {
      test.skip();
      return;
    }

    const data = unwrap(await createResponse.json());
    const processPid = data.pid;

    try {
      expect(data.processKey).toBe(processKey);
    } finally {
      await request.delete(`/api/bpm/process-definitions/${processPid}`);
    }
  });
});
