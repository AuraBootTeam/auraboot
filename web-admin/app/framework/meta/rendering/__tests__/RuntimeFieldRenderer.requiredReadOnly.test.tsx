import { resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import React from 'react';
import { render, waitFor, cleanup } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

/**
 * A read-only field is never user-required: it is system-managed / auto-generated
 * (e.g. an auto-numbered `sc_code` like `SC-20260618-013`) and the user cannot
 * type into it. The required marker (`*`) and the "此字段为必填项" error must be
 * suppressed so the display matches the submit gate, which already excludes
 * read-only fields from required validation
 * (`FormPageContent`: `!rawField.readOnly && (rawField.required ?? meta.required)`).
 *
 * Regression: showcase create form rendered 编号 (sc_code) read-only yet with a
 * required `*`, misleading the user into thinking an auto-generated field was a
 * mandatory input.
 */
describe('RuntimeFieldRenderer required vs read-only', () => {
  const buildRuntime = (fieldMeta: Record<string, unknown> | undefined, locale = 'zh-CN', translations: Record<string, string> = {}) => {
    const context = { locale, t: (k: string) => translations[k] ?? k, record: { sc_status: 'active' }, state: {}, form: {} };
    const stateManager = {
      getFieldMeta: () => fieldMeta,
      getFieldValue: () => undefined,
      updateField: vi.fn(),
      updateState: vi.fn(),
    };
    return {
      getContext: () => context,
      getStateManager: () => stateManager,
      getScopeId: () => 'scope-1',
      getDataSourceManager: () => ({ notifyStateChanged: vi.fn() }),
      triggerFieldLinkage: vi.fn(),
    } as any;
  };

  const renderField = async (field: any, fieldMeta: Record<string, unknown> | undefined, locale = 'zh-CN', translations: Record<string, string> = {}) => {
    let captured: any;
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: (p: any) => {
        captured = p;
        return <div data-testid="cl" />;
      },
    }));
    const { RuntimeFieldRenderer } = await import('../RuntimeFieldRenderer');
    render(<RuntimeFieldRenderer field={field} runtime={buildRuntime(fieldMeta, locale, translations)} />);
    await waitFor(() => expect(captured).toBeTruthy());
    return captured;
  };

  it('suppresses required on a read-only field even when fieldMeta.required is true', async () => {
    const captured = await renderField(
      { field: 'sc_code', component: 'SmartInput', readOnly: true } as any,
      { required: true },
    );
    expect(captured.props.readOnly).toBe(true);
    expect(captured.props.required).toBe(false);
  });

  it('keeps required on an editable field when fieldMeta.required is true', async () => {
    const captured = await renderField(
      { field: 'sc_name', component: 'SmartInput' } as any,
      { required: true },
    );
    expect(captured.props.readOnly).toBeFalsy();
    expect(captured.props.required).toBe(true);
  });

  it('does not forward field-governance metadata to the rendered control', async () => {
    const captured = await renderField(
      {
        field: 'quote_code',
        component: 'SmartInput',
        props: {
          immutableWhen: { field: 'formal', in: [true] },
          allowedWriterCommands: ['quote:publish'],
          placeholder: 'Quote code',
        },
      } as any,
      undefined,
    );
    expect(captured.props).not.toHaveProperty('immutableWhen');
    expect(captured.props).not.toHaveProperty('allowedWriterCommands');
    expect(captured.props.placeholder).toBe('Quote code');
  });

  for (const locale of ['zh-CN', 'en-US']) {
    it(`resolves every Showcase configured placeholder in ${locale} without mutating the page`, async () => {
      const page = JSON.parse(readFileSync(resolve(import.meta.dirname, '../../../../../../plugins/showcase/config/pages/showcase_all_fields_form.json'), 'utf8'));
      const catalog = JSON.parse(readFileSync(resolve(import.meta.dirname, '../../../../../../plugins/showcase/config/i18n.json'), 'utf8'));
      const translations = Object.fromEntries(catalog.map((entry: any) => [entry.key, entry[locale]]));
      const original = JSON.stringify(page);
      const fields: any[] = [];
      const collect = (node: any) => {
        if (Array.isArray(node)) node.forEach(collect);
        else if (node && typeof node === 'object') {
          if (node.field && node.props?.placeholder !== undefined) fields.push(node);
          Object.values(node).forEach(collect);
        }
      };
      collect(page);
      expect(fields).toHaveLength(24);
      const localized = fields.filter((field) => field.props.placeholder.startsWith('$i18n:'));
      expect(localized).toHaveLength(20);
      for (const field of fields) {
        const key = `model.showcase_all_fields.${field.field}.placeholder`;
        const expected = translations[key] ?? field.props.placeholder;
        expect(expected).toBeTruthy();
        expect(expected).not.toMatch(/^\$i18n:/);
        if (locale === 'en-US') expect(expected).not.toMatch(/[\u4e00-\u9fff]/);
        const captured = await renderField(field, undefined, locale, translations);
        expect(captured.props.placeholder).toBe(expected);
        if (field.readOnly) expect(captured.props.readOnly).toBe(true);
        cleanup();
      }
      expect(JSON.stringify(page)).toBe(original);
    });
  }

});
