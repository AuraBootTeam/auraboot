import { createServer } from 'node:http';
import { once } from 'node:events';
import { expect, type Page } from '@playwright/test';

// Both original provider journeys use an owned local connection, never the copied live credentials.
export async function createControlledQuoteProvider(page: Page) {
  let outcome: 'failed' | 'suggested' = 'failed';
  let requests = 0;
  const server = createServer(async (request, response) => {
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(Buffer.from(chunk));
    const payload = JSON.parse(Buffer.concat(chunks).toString());
    requests++;
    expect(request.url).toBe('/v1/chat/completions');
    expect(payload.model).toBe('q07-local-controlled');
    response.setHeader('content-type', 'application/json');
    if (outcome === 'failed') {
      response.writeHead(503);
      response.end(JSON.stringify({ error: { message: 'Controlled local provider unavailable' } }));
    } else {
      const input = JSON.parse(
        payload.messages.filter((message: any) => message.role === 'user').at(-1).content,
      );
      response.end(
        JSON.stringify({
          id: 'q07-controlled',
          object: 'chat.completion',
          model: payload.model,
          choices: [
            {
              index: 0,
              finish_reason: 'stop',
              message: {
                role: 'assistant',
                content: JSON.stringify({
                  suggestions: input.lines.map((line: any) => ({
                    lineId: line.lineId,
                    mpn: line.mpn,
                    unitPrice: 1.25,
                    currency: 'CNY',
                    confidence: 0.8,
                    sourceRef: 'q07-local',
                    reason: 'Manual review required',
                  })),
                }),
              },
            },
          ],
          usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 },
        }),
      );
    }
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  expect(address && typeof address !== 'string').toBeTruthy();
  const providerCode = `q07-${Date.now()}-${Math.random().toString(16).slice(2, 7)}`;
  let configPid = '';
  try {
    const beforeResponse = await page.request.get('/api/llm-config?level=tenant');
    expect(beforeResponse.ok()).toBe(true);
    const before = await beforeResponse.json();
    expect(String(before.code)).toBe('0');
    const priority = -2147483648;
    expect(
      before.data.every((config: any) => Number(config.priority ?? 0) > priority),
      'the owned loopback provider must be selected ahead of existing tenant providers',
    ).toBe(true);
    const configResponse = await page.request.post('/api/llm-config', {
      data: {
        configLevel: 'tenant',
        serviceType: 'llm',
        providerCode,
        enabled: true,
        priority,
        config: JSON.stringify({
          apiFormat: 'chat_completions',
          baseUrl: `http://127.0.0.1:${(address as any).port}/v1`,
          apiKey: 'q07-local-only',
          defaultModel: 'q07-local-controlled',
          maxTokens: 1000,
        }),
      },
    });
    expect(configResponse.ok()).toBe(true);
    expect(String((await configResponse.json()).code)).toBe('0');
    const selected = await (await page.request.get('/api/llm-config?level=tenant')).json();
    const owned = selected.data.filter((config: any) => config.providerCode === providerCode);
    expect(owned).toHaveLength(1);
    configPid = owned[0].pid;
    return {
      get requests() {
        return requests;
      },
      set outcome(value: 'failed' | 'suggested') {
        outcome = value;
      },
      async close() {
        try {
          const after = await (await page.request.get('/api/llm-config?level=tenant')).json();
          expect(
            after.data.filter((config: any) => config.pid !== configPid),
            'pre-existing configurations are unchanged',
          ).toEqual(before.data);
          const deleted = await page.request.delete(`/api/llm-config/${configPid}`);
          expect(deleted.ok()).toBe(true);
          expect(String((await deleted.json()).code)).toBe('0');
        } finally {
          await new Promise<void>((resolve, reject) =>
            server.close((error) => (error ? reject(error) : resolve())),
          );
        }
      },
    };
  } catch (error) {
    if (configPid) await page.request.delete(`/api/llm-config/${configPid}`);
    await new Promise<void>((resolve, reject) =>
      server.close((failure) => (failure ? reject(failure) : resolve())),
    );
    throw error;
  }
}
