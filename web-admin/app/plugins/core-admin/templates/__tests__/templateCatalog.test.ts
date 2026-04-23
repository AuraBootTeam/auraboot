import { describe, expect, it } from 'vitest';
import {
  inferTemplateSource,
  mergeTemplateCatalog,
  type TemplateRegistryEntry,
} from '../templateCatalog';

describe('templateCatalog merge helpers', () => {
  it('keeps curated OSS metadata while merging runtime catalog entries', () => {
    const templates = mergeTemplateCatalog([
      {
        id: 'crm-quick-start',
        name: 'CRM Quick Start',
        relativePath: 'plugins/crm-quick-start',
        namespace: 'tcrm',
      },
    ]);

    const template = templates.find((item) => item.id === 'crm-quick-start');

    expect(template).toMatchObject({
      id: 'crm-quick-start',
      name: 'CRM Quick Start',
      pluginPath: 'plugins/crm-quick-start',
      namespace: 'tcrm',
      source: 'oss',
      modelCount: 4,
    });
    expect(template?.features).toContain('Lead Management');
  });

  it('adds dynamically discovered enterprise templates with inferred metadata', () => {
    const templates = mergeTemplateCatalog([
      {
        id: 'enterprise-hr',
        name: 'Enterprise HR Suite',
        relativePath: 'plugins/templates/enterprise-hr',
        namespace: 'ehr',
      },
    ]);

    const template = templates.find((item) => item.id === 'enterprise-hr');

    expect(template).toMatchObject({
      id: 'enterprise-hr',
      source: 'enterprise',
      category: 'HR',
      pluginPath: 'plugins/templates/enterprise-hr',
      namespace: 'ehr',
    });
    expect(template?.features).toContain('Enterprise Edition');
  });

  it('keeps curated OSS templates pinned ahead of dynamic entries', () => {
    const discovered: TemplateRegistryEntry[] = [
      {
        id: 'enterprise-compliance',
        name: 'Enterprise Compliance',
        relativePath: 'plugins/templates/enterprise-compliance',
        namespace: 'ecm',
      },
      {
        id: 'crm-quick-start',
        name: 'CRM Quick Start',
        relativePath: 'plugins/crm-quick-start',
        namespace: 'tcrm',
      },
    ];

    const templates = mergeTemplateCatalog(discovered);

    expect(templates[0]?.id).toBe('crm-quick-start');
  });

  it('infers source from relative path', () => {
    expect(inferTemplateSource('plugins/golden-path')).toBe('oss');
    expect(inferTemplateSource('plugins/templates/enterprise-asset')).toBe('enterprise');
  });
});
