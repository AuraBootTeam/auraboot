/**
 * BPM Designer — serviceTask with serviceType=http
 *
 * Coverage goal: `JsonToBpmnConverter` emits
 *   <serviceTask class="httpServiceTaskDelegate"
 *                smart:serviceUrl="..."
 *                smart:method="..."
 *                smart:responseVar="..."
 *                smart:timeoutMs="..."
 *                smart:async="true"? />
 *
 * when config = { serviceType: 'http', serviceUrl, method, responseVar, timeoutMs, async }.
 *
 * L1: designerJson persists the config
 * L2: BPMN XML carries smart:class + smart:serviceUrl + smart:method + smart:responseVar + smart:timeoutMs
 * L3 skipped: running this would require an external HTTP echo endpoint.
 */

import { test, expect } from '@playwright/test';
import {
  openDesigner,
  addNode,
  connect,
  configureNode,
  saveProcess,
  deployProcess,
} from '../../helpers/designer-dsl';
import { loginAs } from '../../helpers/wd-fixtures';

const BACKEND = process.env.BACKEND_URL ?? `http://localhost:${process.env.BE_PORT ?? '6443'}`;

test.describe('BPM designer — serviceTask http', { tag: ['@bpm-regression'] }, () => {
  test('serviceType=http with serviceUrl + method + responseVar + timeoutMs — L1/L2', async ({ page, request }) => {
    const ts = Date.now();
    const processKey = `e2e_designer_http_${ts}`;
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');

    await openDesigner(page, { processKey, name: `HTTP ${ts}` });

    await addNode(page, { id: 'start_1', type: 'startEvent', position: { x: 80, y: 200 }, label: 'Start' });
    await addNode(page, { id: 'svc_http', type: 'serviceTask', position: { x: 260, y: 200 }, label: 'HTTP Call' });
    await addNode(page, { id: 'end_1', type: 'endEvent', position: { x: 440, y: 200 }, label: 'End' });

    await configureNode(page, 'svc_http', {
      config: {
        serviceType: 'http',
        serviceUrl: 'https://httpbin.org/post',
        method: 'POST',
        responseVar: 'httpResult',
        timeoutMs: '5000',
        async: true,
      },
    });

    await connect(page, { from: 'start_1', to: 'svc_http' });
    await connect(page, { from: 'svc_http', to: 'end_1' });

    const { processDefinitionId } = await saveProcess(page);
    await deployProcess(page, processDefinitionId);

    // L1
    const pdResp = await request.get(
      `${BACKEND}/api/bpm/process-definitions/${processDefinitionId}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(pdResp.ok()).toBe(true);
    const pdBody = (await pdResp.json()) as Record<string, unknown>;
    const pdData = pdBody.data as Record<string, unknown>;
    const dj = JSON.parse(pdData.designerJson as string) as Record<string, unknown>;
    const nodes = dj.nodes as Array<Record<string, unknown>>;
    const svcNode = nodes.find((n) => n.id === 'svc_http');
    expect(svcNode).toBeDefined();
    const cfg = (svcNode!.data as Record<string, unknown>).config as Record<string, unknown>;
    expect(cfg.serviceType).toBe('http');
    expect(cfg.serviceUrl).toBe('https://httpbin.org/post');
    expect(cfg.method).toBe('POST');
    expect(cfg.responseVar).toBe('httpResult');

    // L2
    const xmlResp = await request.get(
      `${BACKEND}/api/bpm/process-definitions/${processDefinitionId}/bpmn`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(xmlResp.ok()).toBe(true);
    const xmlBody = (await xmlResp.json()) as Record<string, unknown>;
    const xml = xmlBody.data as string;
    const tagMatch = xml.match(
      /<serviceTask[^>]*id=["']svc_http["'][^>]*(?:\/>|>[\s\S]*?<\/serviceTask>)/,
    );
    expect(tagMatch, 'svc_http serviceTask tag must exist').not.toBeNull();
    const tag = tagMatch![0];
    expect(tag).toContain('httpServiceTaskDelegate');
    expect(tag).toContain('serviceUrl="https://httpbin.org/post"');
    expect(tag).toContain('method="POST"');
    expect(tag).toContain('responseVar="httpResult"');
    expect(tag).toContain('timeoutMs="5000"');
    expect(tag).toContain('async="true"');
  });
});
