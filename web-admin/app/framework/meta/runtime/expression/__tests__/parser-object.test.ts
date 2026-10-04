import { describe, expect, it } from 'vitest';
import { ExpressionParser } from '../parser';
import { createExpressionContext } from '../context';

describe('ExpressionParser object plugin', () => {
  const parser = () => new ExpressionParser(createExpressionContext({ form: { score: 3, name: 'Ada' } }));

  it('evaluates nested object and array values using the expression context', () => {
    expect(parser().evaluate('${({ score: form.score + 2, details: { name: form.name }, values: [form.score] })}'))
      .toEqual({ score: 5, details: { name: 'Ada' }, values: [3] });
  });

  it('retains dangerous property rejection inside object values', () => {
    expect(() => parser().evaluate('${({ value: form.constructor })}')).toThrow(/constructor/);
  });
});
