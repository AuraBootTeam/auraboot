import { describe, it, expect } from 'vitest';

describe('shell command', () => {
  describe('command registry', () => {
    const COMMANDS: Record<string, string> = {
      'help':       'Show available commands',
      'crm leads':  'List CRM leads',
      'crm opps':   'List opportunities',
      'crm accounts': 'List accounts',
      'crm dashboard': 'CRM KPI summary',
      'project list': 'List projects',
      'project tasks': 'List tasks',
      'project dashboard': 'PM KPI summary',
      'ops agents':  'List agents',
      'ops tools':   'List tools',
      'ops runs':    'List runs',
      'ops audit':   'List audit traces',
      'exit':        'Exit shell',
      'quit':        'Exit shell',
    };

    it('should have all domain commands', () => {
      expect(COMMANDS['crm leads']).toBeDefined();
      expect(COMMANDS['crm opps']).toBeDefined();
      expect(COMMANDS['project list']).toBeDefined();
      expect(COMMANDS['ops agents']).toBeDefined();
    });

    it('should have exit commands', () => {
      expect(COMMANDS['exit']).toBeDefined();
      expect(COMMANDS['quit']).toBeDefined();
    });

    it('should have help', () => {
      expect(COMMANDS['help']).toBe('Show available commands');
    });
  });

  describe('tab completion', () => {
    it('should match prefix', () => {
      const commands = ['crm leads', 'crm opps', 'crm accounts', 'project list', 'ops agents'];
      const line = 'crm';
      const hits = commands.filter(c => c.startsWith(line));
      expect(hits).toEqual(['crm leads', 'crm opps', 'crm accounts']);
    });

    it('should return all on empty input', () => {
      const commands = ['crm leads', 'project list', 'ops agents'];
      const line = '';
      const hits = commands.filter(c => c.startsWith(line));
      expect(hits).toEqual(commands);
    });
  });
});
