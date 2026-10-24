import { describe, expect, it } from 'vitest';
import { createEmptyReport } from '../../types';
import {
  parseReportTemplate,
  REPORT_TEMPLATE_MAX_BYTES,
  serializeReportTemplate,
} from '../reportTemplateIO';
const fixture = () => ({
  ...createEmptyReport('Customer analysis'),
  dataSources: { customers: { type: 'model' as const, modelCode: 'tpm_customer' } },
  body: [
    {
      id: 'customer_table',
      blockType: 'table' as const,
      dataSource: 'customers',
      columns: [{ field: 'name', label: 'Customer' }],
    },
  ],
});
describe('report design template exchange', () => {
  it('round-trips the current definition without mutating it or adding persistent identity', () => {
    const report = fixture();
    const snapshot = structuredClone(report);
    const raw = serializeReportTemplate(report);
    expect(parseReportTemplate(raw)).toEqual(report);
    expect(report).toEqual(snapshot);
    expect(Object.keys(JSON.parse(raw))).toEqual(['format', 'version', 'dsl']);
  });
  it.each([
    'not json',
    JSON.stringify(fixture()),
    JSON.stringify({ format: 'auraboot.report-template', version: 2, dsl: fixture() }),
    JSON.stringify({
      format: 'auraboot.report-template',
      version: 1,
      dsl: fixture(),
      pid: 'foreign-report',
    }),
  ])('rejects malformed, unsupported and identity-bearing artifacts', (raw) => {
    expect(() => parseReportTemplate(raw)).toThrow();
  });
  it.each([
    { body: [{ id: 'broken', blockType: 'table', dataSource: 'customers', columns: 'invalid' }] },
    { dataSources: { customers: { type: 'model' } } },
    { body: [{ id: 'broken', blockType: 'unknown' }] },
    { body: [{ id: 'broken', blockType: 'table', dataSource: 'missing', columns: [] }] },
    {
      body: [
        { id: 'same', blockType: 'rich-text', content: 'A' },
        { id: 'same', blockType: 'rich-text', content: 'B' },
      ],
    },
    { pid: 'foreign-report' },
  ])('rejects structurally invalid report definitions (%j)', (change) => {
    expect(() =>
      parseReportTemplate(
        JSON.stringify({
          format: 'auraboot.report-template',
          version: 1,
          dsl: { ...fixture(), ...change },
        }),
      ),
    ).toThrow();
  });
  it('rejects oversized artifacts before parsing', () => {
    expect(() => parseReportTemplate(' '.repeat(REPORT_TEMPLATE_MAX_BYTES + 1))).toThrow(
      'too large',
    );
  });
  it('accepts authored static rows without querying any backend', () => {
    const report = {
      ...createEmptyReport('Static notes'),
      dataSources: { notes: { type: 'static' as const, data: [{ note: 'Example' }] } },
    };
    expect(parseReportTemplate(serializeReportTemplate(report))).toEqual(report);
  });
});

it('preserves an optional AI analysis heading in a rich-text block', () => {
  const report = {
    ...createEmptyReport('Analysis'),
    body: [
      {
        id: 'notes',
        blockType: 'rich-text' as const,
        title: 'Analysis notes',
        content: 'Follow up with the customer',
      },
    ],
  };
  expect(parseReportTemplate(serializeReportTemplate(report)).body[0]).toMatchObject({
    title: 'Analysis notes',
  });
});

it('round-trips scoped named-query business parameters', () => {
  const report = { ...createEmptyReport('Project review'), dataSources: { rows: {
    type: 'namedQuery' as const, queryCode: 'project_review',
    params: { projectPid: 'project-a', includeCompleted: true, phase: 2 },
  } } };
  expect(parseReportTemplate(serializeReportTemplate(report))).toEqual(report);
});
