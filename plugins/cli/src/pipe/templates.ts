import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseWorkflowFile } from './parser.js';
import type { WorkflowDefinition } from './types.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const TEMPLATES_DIR = path.join(__dirname, 'templates');

export interface TemplateInfo {
  name: string;
  description: string;
  filePath: string;
  version?: string;
}

/**
 * List all built-in workflow templates.
 */
export function listTemplates(): TemplateInfo[] {
  if (!fs.existsSync(TEMPLATES_DIR)) return [];

  return fs.readdirSync(TEMPLATES_DIR)
    .filter(f => f.endsWith('.yaml') || f.endsWith('.yml') || f.endsWith('.json'))
    .map(f => {
      const filePath = path.join(TEMPLATES_DIR, f);
      try {
        const def = parseWorkflowFile(filePath);
        return {
          name: def.name,
          description: def.description || '',
          filePath,
          version: def.version,
        };
      } catch {
        return {
          name: path.basename(f, path.extname(f)),
          description: '(parse error)',
          filePath,
        };
      }
    });
}

/**
 * Load a built-in template by name.
 */
export function loadTemplate(name: string): WorkflowDefinition | null {
  const templates = listTemplates();
  const match = templates.find(t => t.name === name);
  if (!match) return null;
  return parseWorkflowFile(match.filePath);
}

/**
 * Get the template directory path (for copying templates to user workspace).
 */
export function getTemplatesDir(): string {
  return TEMPLATES_DIR;
}
