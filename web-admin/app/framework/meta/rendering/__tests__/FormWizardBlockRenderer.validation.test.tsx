import React, { useEffect, useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ScopedStateManager } from '../../runtime/state/scoped-state';
import { FormWizardBlockRenderer } from '../blocks/FormWizardBlockRenderer';
import { NativeFormWizardProvider, FormWizardActionGate } from '../NativeFormWizardContext';
import { useFormValidation } from '../pages/formPage/useFormValidation';
import { BlockRenderer } from '@auraboot/runtime-kernel';
import { renderFormPageView } from '../pages/formPage/renderFormPageView';

// Substitute child presentation only. Navigation stays in the actual wizard;
// entered values stay in the actual scoped form store. No validator is mocked.
vi.mock('@auraboot/runtime-kernel', () => ({
  BlockErrorBoundary: ({ children }: any) => children,
  BlockRenderer: ({ block, runtime }: any) =>
    block.blockType === 'form-wizard' ? (
      <FormWizardBlockRenderer block={block} runtime={runtime} />
    ) : (
      <section aria-label={block.id}>
        {(block.fields || []).map((field: any) => (
          <input
            key={field.field}
            aria-label={field.field}
            required={field.required}
            defaultValue={
              runtime.getStateManager().getFieldValue(runtime.getScopeId(), field.field) ?? ''
            }
            onChange={(event) =>
              runtime
                .getStateManager()
                .updateField(runtime.getScopeId(), field.field, event.target.value)
            }
          />
        ))}
      </section>
    ),
}));

afterEach(cleanup);

function setup(options: { locale?: string; ready?: boolean; submitting?: boolean } = {}) {
  const state = new ScopedStateManager({} as any);
  state.createScope('onboarding-probe', { form: { applicationName: '' } });
  const runtime = {
    getStateManager: () => state,
    getScopeId: () => 'onboarding-probe',
    getContext: () => ({ locale: options.locale || 'en', ...state.getScope('onboarding-probe') }),
  } as any;
  const block = {
    id: 'onboarding',
    blockType: 'form-wizard',
    steps: [
      {
        key: 'identity',
        label: 'Identity',
        blocks: [
          {
            id: 'identity-fields',
            blockType: 'form-section',
            fields: [{ field: 'applicationName', required: true }],
          },
        ],
      },
      {
        key: 'review',
        label: 'Review',
        blocks: [{ id: 'review-fields', blockType: 'form-section', fields: [] }],
      },
    ],
  } as any;
  function Host() {
    const [formData, setFormData] = useState(state.getScope('onboarding-probe')!.form);
    useEffect(() => state.subscribe('onboarding-probe', (scope) => scope.form, setFormData), []);
    const { validateFormBeforeSubmit } = useFormValidation({
      schema: { blocks: [block] },
      recordPid: null,
      initialFormData: {},
      formData,
      pageContext: { form: formData },
      modelFields: {},
      t: (key: string) => key,
      locale: options.locale || 'en',
      hasModelCodeAndKindFields: false,
      kindCapabilities: null,
      showErrorToast: vi.fn(),
    } as any);
    return (
      <NativeFormWizardProvider
        wizardIds={['onboarding']}
        ready={options.ready ?? true}
        submitting={options.submitting ?? false}
        validateStep={(blocks) =>
          Object.keys(validateFormBeforeSubmit(blocks).fieldErrors).length === 0
        }
        renderStep={(blocks) =>
          blocks.map((child) => <BlockRenderer key={child.id} block={child} runtime={runtime} />)
        }
      >
        <FormWizardBlockRenderer block={block} runtime={runtime} />
        <FormWizardActionGate bypass={false}>
          <button type="button">Save application</button>
        </FormWizardActionGate>
        <FormWizardActionGate bypass>
          <button type="button">Cancel</button>
        </FormWizardActionGate>
      </NativeFormWizardProvider>
    );
  }
  render(<Host />);
  return state;
}

describe('Existing wizard step validation investigation', () => {
  it('keeps an invalid required-field step active when Next is selected', () => {
    const state = setup();
    const input = screen.getByRole('textbox', { name: 'applicationName' }) as HTMLInputElement;
    expect(input.required).toBe(true);
    expect(input.checkValidity()).toBe(false);
    expect(state.getFieldValue('onboarding-probe', 'applicationName')).toBe('');
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.queryByRole('region', { name: 'identity-fields' })).not.toBeNull();
    expect(screen.queryByRole('region', { name: 'review-fields' })).toBeNull();
  });

  it('advances after input and preserves the real stored value on return', () => {
    const state = setup();
    fireEvent.change(screen.getByRole('textbox', { name: 'applicationName' }), {
      target: { value: 'BOM and Quote' },
    });
    expect(state.getFieldValue('onboarding-probe', 'applicationName')).toBe('BOM and Quote');
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.queryByRole('region', { name: 'review-fields' })).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
    expect(
      (screen.getByRole('textbox', { name: 'applicationName' }) as HTMLInputElement).value,
    ).toBe('BOM and Quote');
  });

  it('shows native submit only at the last step while cancellation remains available', () => {
    setup();
    expect(screen.queryByRole('button', { name: 'Save application' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeTruthy();
    fireEvent.change(screen.getByRole('textbox', { name: 'applicationName' }), {
      target: { value: 'BOM and Quote' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByRole('button', { name: 'Save application' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Submit' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
    expect(screen.queryByRole('button', { name: 'Save application' })).toBeNull();
  });

  it('blocks navigation until native field metadata is ready and uses localized labels', () => {
    setup({ locale: 'zh-CN', ready: false });
    expect((screen.getByRole('button', { name: '校验并继续' }) as HTMLButtonElement).disabled).toBe(
      true,
    );
    expect(screen.getByText('第 1 步，共 2 步')).toBeTruthy();
  });

  it('blocks navigation while a native submission is in flight', () => {
    setup({ submitting: true });
    expect((screen.getByRole('button', { name: 'Next' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('fails visibly outside a native form instead of exposing a no-op submit', () => {
    render(
      <FormWizardBlockRenderer
        block={
          {
            id: 'unhosted',
            blockType: 'form-wizard',
            steps: [{ key: 'one', label: 'One', blocks: [] }],
          } as any
        }
        runtime={{ getContext: () => ({ locale: 'en' }) } as any}
      />,
    );
    expect(screen.getByRole('alert').textContent).toBe('This wizard requires a form page.');
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('uses the native view metadata, validation and declared footer action', () => {
    const dispatch = vi.fn();
    const metadata = {
      applicationName: { dataType: 'string', displayName: 'Application name', required: true },
    };
    const schema = {
      pageKey: 'test_onboard',
      modelCode: 'test_application',
      kind: 'form',
      extension: {},
      blocks: [
        {
          id: 'onboarding',
          blockType: 'form-wizard',
          steps: [
            {
              key: 'identity',
              label: 'Identity',
              blocks: [
                {
                  id: 'identity',
                  blockType: 'form-section',
                  fields: [{ field: 'applicationName' }],
                },
              ],
            },
            {
              key: 'review',
              label: 'Review',
              blocks: [
                { id: 'review', blockType: 'form-section', title: 'Review details', fields: [] },
              ],
            },
          ],
        },
        {
          id: 'actions',
          blockType: 'form-buttons',
          buttons: [
            {
              code: 'submit',
              action: 'save',
              commandCode: 'test:create_application',
              primary: true,
              label: 'Save application',
            },
            { code: 'cancel', action: 'cancel', label: 'Cancel' },
          ],
        },
      ],
    };
    function NativeView() {
      const [formData, setFormData] = useState<Record<string, string>>({});
      const pageContext = { locale: 'en', form: formData };
      const { validateFormBeforeSubmit } = useFormValidation({
        schema,
        recordPid: null,
        initialFormData: {},
        formData,
        pageContext,
        modelFields: metadata,
        t: (key: string) => key,
        locale: 'en',
        hasModelCodeAndKindFields: false,
        kindCapabilities: null,
        showErrorToast: vi.fn(),
      } as any);
      return renderFormPageView({
        schema,
        mainRecordLoaded: true,
        schemaFieldNames: new Set(['applicationName']),
        fieldMetaLoaded: true,
        tableName: 'test_application',
        pageContext,
        hasPermission: () => true,
        dataSourceManager: {},
        aiFill: { target: { snapshot: () => ({ fields: [] }) } },
        aiFields: [],
        isEditMode: false,
        locale: 'en',
        t: (key: string) => key,
        onCancelOverride: vi.fn(),
        summaryErrors: [],
        fieldErrors: {},
        fieldPermissions: {},
        modelFields: metadata,
        formData,
        runtime: { getContext: () => pageContext },
        subTableBlocks: [],
        validateWizardStep: (blocks: any[]) =>
          Object.keys(validateFormBeforeSubmit(blocks).fieldErrors).length === 0,
        handleFormAction: dispatch,
        renderSmartField: (field: any) => (
          <input
            aria-label={field.label}
            required={field.required}
            value={formData[field.field] || ''}
            onChange={(event) => setFormData({ ...formData, [field.field]: event.target.value })}
          />
        ),
      } as any);
    }
    render(<NativeView />);
    expect(
      (screen.getByRole('textbox', { name: 'Application name' }) as HTMLInputElement).required,
    ).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.queryByRole('button', { name: 'Save application' })).toBeNull();
    fireEvent.change(screen.getByRole('textbox', { name: 'Application name' }), {
      target: { value: 'BOM and Quote' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Review details')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save application' }));
    expect(dispatch).toHaveBeenCalledWith(
      expect.objectContaining({ commandCode: 'test:create_application' }),
    );
    expect(dispatch).toHaveBeenCalledTimes(1);
  });
});
