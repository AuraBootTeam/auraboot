import {describe, expect, it} from 'vitest';
import {AUTOMATION_TEMPLATE_MAX_NODES, parseAutomationTemplate} from '../automationTemplateIO';

const node = {id: 'start', type: 'trigger-manual', position: {x: 10, y: 20}, data: {label: 'Start', config: {}}};
const template = {name: 'Review copy', flowConfig: {nodes: [node], edges: []}};

describe('Automation portable import boundary', () => {
  it.each([{}, {version: 1}, {flowData: template.flowConfig, flowConfig: undefined}])('accepts historical DTO exports and version 1 while stripping identity and enablement: %j', extra => {
    const parsed = parseAutomationTemplate(JSON.stringify({...template, ...extra, pid: 'old', tenantId: 42, enabled: true, actions: [{type: 'write_audit'}]}));
    expect(parsed).toEqual(template);
  });
  it.each([
    ['unknown version', {...template, version: 2}],
    ['missing name', {...template, name: ''}],
    ['broken graph', {...template, flowConfig: {nodes: {}, edges: []}}],
    ['duplicate nodes', {...template, flowConfig: {nodes: [node, node], edges: []}}],
    ['missing node config', {...template, flowConfig: {nodes: [{...node, data: {label: 'Start'}}], edges: []}}],
    ['dangling edge', {...template, flowConfig: {nodes: [node], edges: [{id: 'edge', source: 'start', target: 'missing'}]}}],
    ['duplicate edges', {...template, flowConfig: {nodes: [node], edges: Array(2).fill({id: 'edge', source: 'start', target: 'start'})}}],
    ['node capacity', {...template, flowConfig: {nodes: Array.from({length: AUTOMATION_TEMPLATE_MAX_NODES + 1}, (_, i) => ({...node, id: `n${i}`})), edges: []}}],
    ['prototype key', JSON.parse('{"name":"Unsafe","flowConfig":{"nodes":[],"edges":[]},"__proto__":{"polluted":true}}')],
  ])('rejects %s', (_, value) => expect(() => parseAutomationTemplate(JSON.stringify(value))).toThrow());
  it('rejects non-JSON and files over 2 MB before reading the graph', () => {
    expect(() => parseAutomationTemplate('{broken')).toThrow();
    expect(() => parseAutomationTemplate(' '.repeat(2 * 1024 * 1024 + 1))).toThrow('size');
  });
});


const parameter = {key: 'target', kind: 'record', modelCode: 'demo_task', displayField: 'title', label: 'Target'};
const parameterized = {...template, templateParameters: [parameter], flowConfig: {nodes: [{...node, data: {...node.data, config: {expression: "recordId == '{{template.target}}'"}}}], edges: []}};
describe('Automation typed template parameters', () => {
  it('preserves the declared input without importing identity, values or enablement', () => {
    const result = parseAutomationTemplate(JSON.stringify({...parameterized, templateValues: {target: 'FOREIGN'}, enabled: true, tenantId: 55}));
    expect(result.templateParameters).toEqual([parameter]);
    expect(result).not.toHaveProperty('templateValues'); expect(result).not.toHaveProperty('enabled');
  });
  it.each([
    {...parameterized, templateParameters: undefined},
    {...parameterized, templateParameters: []},
    {...parameterized, templateParameters: [parameter, parameter]},
    {...parameterized, templateParameters: [{...parameter, kind: 'sql'}]},
    {...parameterized, templateParameters: [{...parameter, modelCode: '../private'}]},
    {...parameterized, templateParameters: [{...parameter, key: 'other'}]},
    {...parameterized, templateParameters: [{...parameter, displayField: ''}]},
  ])('rejects malformed, duplicate and unresolved parameter contracts', input => {
    expect(() => parseAutomationTemplate(JSON.stringify(input))).toThrow();
  });
});
