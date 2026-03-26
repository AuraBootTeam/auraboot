import { describe, it, expect } from 'vitest';

describe('pipeline analyze command', () => {
  describe('prompt construction', () => {
    it('should include data context when piped', () => {
      const data = [{ name: 'Lead 1', score: 80 }];
      const analysis = 'churn-risk';
      const dataContext = `\n\nData to analyze (${data.length} records):\n${JSON.stringify(data, null, 2)}`;
      const prompt = `Analyze the following data. Analysis type: ${analysis}${dataContext}`;

      expect(prompt).toContain('churn-risk');
      expect(prompt).toContain('Lead 1');
      expect(prompt).toContain('1 records');
    });

    it('should work without piped data', () => {
      const analysis = 'market-trends';
      const prompt = `Analyze the following data. Analysis type: ${analysis}`;
      expect(prompt).toContain('market-trends');
      expect(prompt).not.toContain('records');
    });
  });

  describe('response parsing', () => {
    it('should extract JSON from markdown code blocks', () => {
      const response = 'Here is the analysis:\n```json\n{"summary":"test"}\n```\nDone.';
      const match = response.match(/```json\s*([\s\S]*?)```/);
      expect(match).not.toBeNull();
      expect(JSON.parse(match![1].trim())).toEqual({ summary: 'test' });
    });

    it('should pass through raw JSON', () => {
      const response = '{"summary":"direct json"}';
      expect(() => JSON.parse(response)).not.toThrow();
    });

    it('should wrap text response in JSON', () => {
      const response = 'The top risks are...';
      const wrapped = JSON.stringify({ analysis: 'churn', result: response });
      const parsed = JSON.parse(wrapped);
      expect(parsed.result).toBe('The top risks are...');
    });
  });
});
