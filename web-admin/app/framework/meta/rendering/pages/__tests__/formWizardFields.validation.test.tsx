import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useFormValidation } from '../formPage/useFormValidation';
import { collectFormFieldDataTypes } from '../formPage/formRecordValues';
import { collectSubmitPayloadFieldTypes } from '../formPage/formPayload';
import { collectFormSections } from '../../../schemas/formSections';

// The same field contract must survive moving a section into a wizard step.
// Exercise the existing validator and payload collectors without replacements.
const section = {
  id: 'source-section',
  blockType: 'form-section',
  fields: [
    {
      field: 'sourceBindings',
      label: 'Source bindings',
      required: true,
      dataType: 'jsonb',
      submitPayload: true,
    },
  ],
};
const wizard = {
  id: 'onboarding',
  blockType: 'form-wizard',
  steps: [{ key: 'sources', label: 'Sources', blocks: [section] }],
};

function validate(blocks: unknown[]) {
  const { result } = renderHook(() =>
    useFormValidation({
      schema: { modelCode: 'test_application', blocks },
      recordPid: null,
      initialFormData: {},
      formData: {},
      pageContext: { form: {}, record: {} },
      modelFields: {},
      t: (key: string) => key,
      locale: 'en',
      hasModelCodeAndKindFields: false,
      kindCapabilities: null,
      showErrorToast: vi.fn(),
    } as any),
  );
  return result.current.validateFormBeforeSubmit();
}

describe('Wizard fields retain the native form contract', () => {
  it('rejects the empty required field in a top-level section (control)', () => {
    expect(validate([section]).fieldErrors.sourceBindings).toBeTruthy();
  });

  it('rejects the same empty required field inside a wizard on final validation', () => {
    expect(validate([wizard]).fieldErrors.sourceBindings).toBeTruthy();
  });

  it('keeps JSON field types for fields inside wizard steps', () => {
    expect(collectFormFieldDataTypes([section])).toEqual({ sourceBindings: 'jsonb' });
    expect(collectFormFieldDataTypes([wizard])).toEqual({ sourceBindings: 'jsonb' });
  });

  it('keeps explicitly declared command payload fields inside wizard steps', () => {
    expect(collectSubmitPayloadFieldTypes([section])).toEqual({ sourceBindings: 'jsonb' });
    expect(collectSubmitPayloadFieldTypes([wizard])).toEqual({ sourceBindings: 'jsonb' });
  });

  it('respects a hidden wizard ancestor during final validation', () => {
    const hidden = { ...wizard, visibleWhen: 'form.showSources === true' };
    expect(validate([hidden]).fieldErrors).toEqual({});
  });

  it('validates only the current step on Next and all steps on final submit', () => {
    const future = { ...section, fields: [{ field: 'futureValue', required: true }] };
    const { result } = renderHook(() =>
      useFormValidation({
        schema: { blocks: [section, future] },
        recordPid: null,
        initialFormData: {},
        formData: { sourceBindings: [{ repository: 'core' }] },
        pageContext: { form: {} },
        modelFields: {},
        t: (key: string) => key,
        locale: 'en',
        hasModelCodeAndKindFields: false,
        kindCapabilities: null,
        showErrorToast: vi.fn(),
      } as any),
    );
    expect(result.current.validateFormBeforeSubmit([section]).fieldErrors).toEqual({});
    expect(result.current.validateFormBeforeSubmit().fieldErrors.futureValue).toBeTruthy();
  });

  it('blocks Next and final submission for syntactically valid JSON with missing nested fields', () => {
    const current = {
      ...section,
      fields: [
        {
          field: 'definitionDraft',
          dataType: 'jsonb',
          required: true,
          submitPayload: true,
          props: {
            mode: 'structured',
            structuredSchema: {
              type: 'object',
              required: ['repositories'],
              properties: {
                repositories: {
                  type: 'array',
                  minItems: 1,
                  items: {
                    type: 'object',
                    required: ['name'],
                    properties: {
                      name: { type: 'string', title: 'Repository name', minLength: 1 },
                    },
                  },
                },
              },
            },
          },
        },
      ],
    };
    const { result, rerender } = renderHook(
      ({ value }: { value: string }) =>
        useFormValidation({
          schema: { blocks: [{ ...wizard, steps: [{ blocks: [current] }] }] },
          recordPid: null,
          initialFormData: {},
          formData: { definitionDraft: value },
          pageContext: { form: {} },
          modelFields: {},
          t: (key: string) => key,
          locale: 'en',
          hasModelCodeAndKindFields: false,
          kindCapabilities: null,
          showErrorToast: vi.fn(),
        } as any),
      { initialProps: { value: '{"repositories":[{}]}' } },
    );
    expect(
      result.current.validateFormBeforeSubmit([current]).fieldErrors.definitionDraft,
    ).toContain('Repository name');
    expect(result.current.validateFormBeforeSubmit().fieldErrors.definitionDraft).toContain(
      'Repository name',
    );
    rerender({ value: '{"repositories":[{"name":"Core"}]}' });
    expect(result.current.validateFormBeforeSubmit([current]).fieldErrors).toEqual({});
    expect(result.current.validateFormBeforeSubmit().fieldErrors).toEqual({});
  });

  it('walks nested tabs and steps without flattening child-form and row fields', () => {
    const child = {
      ...section,
      fields: [{ field: 'childOnly', dataType: 'jsonb', submitPayload: true }],
    };
    const blocks = [
      {
        blockType: 'tabs',
        tabs: [
          {
            blocks: [
              wizard,
              ...['repeater', 'subform', 'sub-table'].map((blockType) => ({
                blockType,
                blocks: [child],
              })),
            ],
          },
        ],
      },
    ];
    expect(collectFormSections(blocks)).toEqual([section]);
    expect(collectFormFieldDataTypes(blocks)).toEqual({ sourceBindings: 'jsonb' });
    expect(collectSubmitPayloadFieldTypes(blocks)).toEqual({ sourceBindings: 'jsonb' });
  });
});
