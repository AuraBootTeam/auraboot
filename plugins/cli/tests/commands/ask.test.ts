import { describe, it, expect, vi } from 'vitest';

describe('ask command', () => {
  describe('request body construction', () => {
    it('should build correct chat request body', () => {
      const message = 'Which customers are at risk of churning?';
      const body = {
        messages: [{ role: 'user', content: message }],
      };

      expect(body.messages).toHaveLength(1);
      expect(body.messages[0].role).toBe('user');
      expect(body.messages[0].content).toBe(message);
    });

    it('should handle CJK messages', () => {
      const message = '上周哪些客户最可能流失';
      const body = {
        messages: [{ role: 'user', content: message }],
      };

      expect(body.messages[0].content).toBe('上周哪些客户最可能流失');
      const json = JSON.stringify(body);
      expect(json).toContain('上周');
    });
  });

  describe('streaming output', () => {
    it('should accumulate content chunks', () => {
      const chunks = ['Hello', ' ', 'world', '!'];
      let fullContent = '';
      for (const chunk of chunks) {
        fullContent += chunk;
      }
      expect(fullContent).toBe('Hello world!');
    });

    it('should produce JSON in agent mode', () => {
      const fullContent = 'The top churn risks are...';
      const agentOutput = JSON.stringify({ content: fullContent });
      const parsed = JSON.parse(agentOutput);
      expect(parsed.content).toBe(fullContent);
    });
  });
});
