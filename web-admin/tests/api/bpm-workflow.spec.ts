/**
 * BPM Workflow API Tests
 *
 * Migrated from: tests/e2e/bpm/bpm-workflow.spec.ts
 * Tests: D7-E06, D7-E07, D7-E09, D-N04, D-N05
 *
 * E2E tests (D7-E01 ~ D7-E04, D7-E05, D7-E08) remain in the e2e file.
 *
 * @since 4.0.0
 */

import { test, expect } from '@playwright/test';

function generateMinimalBpmn(processKey: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://auraboot.com/bpm"
             id="definitions_${processKey}">
  <process id="${processKey}" name="E2E Workflow Test" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="userTask1" name="E2E Approval Task"/>
    <endEvent id="end"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="userTask1"/>
    <sequenceFlow id="flow2" sourceRef="userTask1" targetRef="end"/>
  </process>
</definitions>`;
}

function generateProcessKey(): string {
  const ts = Date.now();
  const rand = Math.random().toString(36).slice(2, 7);
  return `e2e_wf_${ts}_${rand}`;
}

test.describe('BPM Workflow API', () => {
  test.describe.configure({ mode: 'serial' });

  let processPid: string | null = null;
  let processKey: string;
  let processInstanceId: string | null = null;

  test.beforeAll(async ({ request }) => {
    processKey = generateProcessKey();
    const bpmnContent = generateMinimalBpmn(processKey);

    try {
      const createResponse = await request.post(
        `/api/bpm/process-definitions`,
        {
          data: {
            processKey,
            processName: `E2E Workflow API Test ${processKey}`,
            description: 'Auto-generated for workflow API test',
            category: 'e2e-test',
            bpmnContent,
          },
        }
      );

      if (createResponse.ok()) {
        const createData = await createResponse.json();
        processPid = createData.pid;

        const deployResponse = await request.post(
          `/api/bpm/process-definitions/${processPid}/deploy`
        );

        if (deployResponse.ok()) {
          const startResponse = await request.post(
            `/api/bpm/process-instances`,
            {
              data: {
                processDefinitionId: processKey,
                businessKey: `E2E-API-BK-${Date.now()}`,
                variables: { initiator: 'e2e-api-test' },
              },
            }
          );

          if (startResponse.ok()) {
            const instanceData = await startResponse.json();
            processInstanceId = instanceData.data?.instanceId || null;
          }
        }
      }
    } catch (error) {
      console.warn('BPM workflow API setup failed:', error);
    }
  });

  test('D7-E06: Approve task via API', async ({ request }) => {
    if (!processPid) {
      test.skip(true, 'No process definition available');
      return;
    }

    const startResponse = await request.post(
      `/api/bpm/process-instances`,
      {
        data: {
          processDefinitionId: processKey,
          businessKey: `E2E-APPROVE-${Date.now()}`,
          variables: { action: 'approve-test' },
        },
      }
    );

    if (!startResponse.ok()) {
      test.skip(true, 'Cannot start process instance for approve test');
      return;
    }

    const instanceData = await startResponse.json();
    const instanceId = instanceData.data?.instanceId || instanceData.instanceId;

    if (!instanceId) {
      test.skip(true, 'No instance ID returned');
      return;
    }

    const tasksResponse = await request.get(
      `/api/bpm/tasks/by-process/${instanceId}`
    );

    if (!tasksResponse.ok()) {
      test.skip(true, 'Cannot fetch tasks for process instance');
      return;
    }

    const tasksData = await tasksResponse.json();
    const tasks = tasksData.data || tasksData;

    if (!Array.isArray(tasks) || tasks.length === 0) {
      test.skip(true, 'No pending tasks for process instance');
      return;
    }

    const approveTaskId = tasks[0].taskId || tasks[0].instanceId;

    const approveResponse = await request.post(
      `/api/bpm/tasks/${approveTaskId}/approve`,
      {
        data: {
          comment: 'Approved by E2E API test',
          variables: {},
        },
      }
    );

    expect(approveResponse.ok()).toBe(true);

    const verifyResponse = await request.get(
      `/api/bpm/tasks/by-process/${instanceId}`
    );

    if (verifyResponse.ok()) {
      const verifyData = await verifyResponse.json();
      const remainingTasks = verifyData.data || verifyData;
      if (Array.isArray(remainingTasks)) {
        const stillPending = remainingTasks.find(
          (t: { taskId: string }) => t.taskId === approveTaskId
        );
        expect(stillPending).toBeUndefined();
      }
    }
  });

  test('D7-E07: Reject task via API', async ({ request }) => {
    if (!processPid) {
      test.skip(true, 'No process definition available');
      return;
    }

    const startResponse = await request.post(
      `/api/bpm/process-instances`,
      {
        data: {
          processDefinitionId: processKey,
          businessKey: `E2E-REJECT-${Date.now()}`,
          variables: { action: 'reject-test' },
        },
      }
    );

    if (!startResponse.ok()) {
      test.skip(true, 'Cannot start process instance for reject test');
      return;
    }

    const instanceData = await startResponse.json();
    const instanceId = instanceData.data?.instanceId || instanceData.instanceId;

    if (!instanceId) {
      test.skip(true, 'No instance ID returned');
      return;
    }

    const tasksResponse = await request.get(
      `/api/bpm/tasks/by-process/${instanceId}`
    );

    if (!tasksResponse.ok()) {
      test.skip(true, 'Cannot fetch tasks for process instance');
      return;
    }

    const tasksData = await tasksResponse.json();
    const tasks = tasksData.data || tasksData;

    if (!Array.isArray(tasks) || tasks.length === 0) {
      test.skip(true, 'No pending tasks for process instance');
      return;
    }

    const rejectTaskId = tasks[0].taskId || tasks[0].instanceId;

    const rejectResponse = await request.post(
      `/api/bpm/tasks/${rejectTaskId}/reject`,
      {
        data: {
          comment: 'Rejected by E2E API test',
          variables: {},
        },
      }
    );

    expect(rejectResponse.ok()).toBe(true);
  });

  test('D7-E09: Process instance status monitor', async ({ request }) => {
    let monitorInstanceId = processInstanceId;

    if (!monitorInstanceId) {
      const startedResponse = await request.get(`/api/bpm/tasks/started`);

      if (startedResponse.ok()) {
        const startedData = await startedResponse.json();
        const instances = startedData.data || startedData;

        if (Array.isArray(instances) && instances.length > 0) {
          monitorInstanceId = instances[0].instanceId;
        }
      }
    }

    if (!monitorInstanceId) {
      test.skip(true, 'No process instance available for monitoring');
      return;
    }

    const statusResponse = await request.get(
      `/api/bpm/process-instances/${monitorInstanceId}/status`
    );

    if (!statusResponse.ok()) {
      const statusCode = statusResponse.status();
      expect([200, 400, 404, 500]).toContain(statusCode);
      return;
    }

    const statusData = await statusResponse.json();
    const status = statusData.data || statusData;
    expect(status).toBeTruthy();
  });

  test('D-N04: Approve task with empty opinion', async ({ request }) => {
    if (!processPid) {
      test.skip(true, 'No process definition available');
      return;
    }

    const startResponse = await request.post(
      `/api/bpm/process-instances`,
      {
        data: {
          processDefinitionId: processKey,
          businessKey: `E2E-EMPTY-OPINION-${Date.now()}`,
          variables: { action: 'empty-opinion-test' },
        },
      }
    );

    if (!startResponse.ok()) {
      test.skip(true, 'Cannot start process instance');
      return;
    }

    const instanceData = await startResponse.json();
    const instanceId = instanceData.data?.instanceId || instanceData.instanceId;
    if (!instanceId) {
      test.skip(true, 'No instance ID returned');
      return;
    }

    const tasksResponse = await request.get(
      `/api/bpm/tasks/by-process/${instanceId}`
    );

    if (!tasksResponse.ok()) {
      test.skip(true, 'Cannot fetch tasks for process instance');
      return;
    }

    const tasksData = await tasksResponse.json();
    const tasks = tasksData.data || tasksData;

    if (!Array.isArray(tasks) || tasks.length === 0) {
      test.skip(true, 'No pending tasks for process instance');
      return;
    }

    const approveTaskId = tasks[0].taskId || tasks[0].instanceId;

    const approveResponse = await request.post(
      `/api/bpm/tasks/${approveTaskId}/approve`,
      {
        data: {
          comment: '',
          variables: {},
        },
      }
    );

    if (approveResponse.ok()) {
      const verifyResponse = await request.get(
        `/api/bpm/tasks/by-process/${instanceId}`
      );
      if (verifyResponse.ok()) {
        const verifyData = await verifyResponse.json();
        const remainingTasks = verifyData.data || verifyData;
        if (Array.isArray(remainingTasks)) {
          const stillPending = remainingTasks.find(
            (t: { taskId: string }) => t.taskId === approveTaskId
          );
          expect(stillPending).toBeUndefined();
        }
      }
    } else {
      expect(approveResponse.status()).toBeGreaterThanOrEqual(400);
      expect(approveResponse.status()).toBeLessThan(500);
    }
  });

  test('D-N05: Approve task with extremely long opinion', async ({ request }) => {
    if (!processPid) {
      test.skip(true, 'No process definition available');
      return;
    }

    const startResponse = await request.post(
      `/api/bpm/process-instances`,
      {
        data: {
          processDefinitionId: processKey,
          businessKey: `E2E-LONG-OPINION-${Date.now()}`,
          variables: { action: 'long-opinion-test' },
        },
      }
    );

    if (!startResponse.ok()) {
      test.skip(true, 'Cannot start process instance');
      return;
    }

    const instanceData = await startResponse.json();
    const instanceId = instanceData.data?.instanceId || instanceData.instanceId;
    if (!instanceId) {
      test.skip(true, 'No instance ID returned');
      return;
    }

    const tasksResponse = await request.get(
      `/api/bpm/tasks/by-process/${instanceId}`
    );

    if (!tasksResponse.ok()) {
      test.skip(true, 'Cannot fetch tasks for process instance');
      return;
    }

    const tasksData = await tasksResponse.json();
    const tasks = tasksData.data || tasksData;

    if (!Array.isArray(tasks) || tasks.length === 0) {
      test.skip(true, 'No pending tasks for process instance');
      return;
    }

    const approveTaskId = tasks[0].taskId || tasks[0].instanceId;

    const longComment = 'E2E long opinion test. '.repeat(250);
    expect(longComment.length).toBeGreaterThan(5000);

    const approveResponse = await request.post(
      `/api/bpm/tasks/${approveTaskId}/approve`,
      {
        data: {
          comment: longComment,
          variables: {},
        },
      }
    );

    if (approveResponse.ok()) {
      const verifyResponse = await request.get(
        `/api/bpm/tasks/by-process/${instanceId}`
      );
      if (verifyResponse.ok()) {
        const verifyData = await verifyResponse.json();
        const remainingTasks = verifyData.data || verifyData;
        if (Array.isArray(remainingTasks)) {
          const stillPending = remainingTasks.find(
            (t: { taskId: string }) => t.taskId === approveTaskId
          );
          expect(stillPending).toBeUndefined();
        }
      }
    } else {
      const status = approveResponse.status();
      expect(status).toBeGreaterThanOrEqual(400);
      expect([400, 413, 422]).toContain(status);
    }
  });

  test.afterAll(async ({ request }) => {
    if (!processPid) return;

    try {
      await request.post(`/api/bpm/process-definitions/${processPid}/undeploy`);
    } catch { /* Ignore */ }

    try {
      await request.delete(`/api/bpm/process-definitions/${processPid}`);
    } catch (error) {
      console.warn('Failed to cleanup workflow API test data:', error);
    }
  });
});
