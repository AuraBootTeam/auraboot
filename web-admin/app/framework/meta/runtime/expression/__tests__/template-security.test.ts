import { Worker } from 'node:worker_threads';
import { describe, expect, it } from 'vitest';
import { ExpressionEvaluator } from '../evaluator';
import { createExpressionContext } from '../context';

function malformedTemplateInWorker(input: string): Promise<{ unchanged: boolean }> {
  const method = ExpressionEvaluator.prototype.evaluateTemplate.toString();
  return new Promise((resolve, reject) => {
    const worker = new Worker(`
      const { parentPort } = require('node:worker_threads');
      const evaluator = { ${method}, evaluate() { throw new Error('No closed expression exists'); } };
      const input = ${JSON.stringify(input)};
      parentPort.postMessage({ unchanged: evaluator.evaluateTemplate(input, {}) === input });
    `, { eval: true });
    const timer = setTimeout(() => {
      void worker.terminate();
      reject(new Error('Malformed interpolation exceeded the isolated three-second CPU budget'));
    }, 3000);
    worker.once('message', (result) => {
      clearTimeout(timer);
      void worker.terminate();
      resolve(result);
    });
    worker.once('error', (error) => {
      clearTimeout(timer);
      void worker.terminate();
      reject(error);
    });
  });
}

describe('template interpolation security', () => {
  it('preserves unclosed expressions without quadratic scanning', async () => {
    const result = await malformedTemplateInWorker('${'.repeat(100_000));
    expect(result.unchanged).toBe(true);
  });

  it('preserves empty expressions and evaluates adjacent valid expressions', () => {
    const evaluator = new ExpressionEvaluator();
    const context = createExpressionContext({ form: { name: 'Acme', amount: 68000 } });
    expect(evaluator.evaluateTemplate('Customer ${form.name}: ${form.amount}', context))
      .toBe('Customer Acme: 68000');
    expect(evaluator.evaluateTemplate('${}${form.name}${}', context)).toBe('${}Acme${}');
    expect(evaluator.evaluateTemplate('Customer ${form.name}; trailing ${', context))
      .toBe('Customer Acme; trailing ${');
  });
});
