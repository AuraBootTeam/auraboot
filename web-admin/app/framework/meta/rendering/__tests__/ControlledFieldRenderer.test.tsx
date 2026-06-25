import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const capturedPropsSpy = vi.fn();

describe('ControlledFieldRenderer', () => {
  beforeEach(() => {
    capturedPropsSpy.mockClear();
  });

  it('resolves model-scoped field labels before probing generic field keys', async () => {
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: ({
        componentName,
        props,
      }: {
        componentName: string;
        props: Record<string, unknown>;
      }) => {
        capturedPropsSpy({ componentName, props });
        return <div data-testid="component-loader">{componentName}</div>;
      },
    }));

    const missingKeyWarnings: string[] = [];
    const t = (key: string) => {
      if (key === 'model.mission.title.label') return '标题';
      missingKeyWarnings.push(key);
      return key;
    };
    const { ControlledFieldRenderer } = await import('../ControlledFieldRenderer');

    render(
      <ControlledFieldRenderer
        field={{ field: 'title', modelCode: 'mission' } as any}
        value={undefined}
        onChange={vi.fn()}
        context={{ locale: 'zh-CN', t } as any}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('component-loader')).toHaveTextContent('SmartInput');
    });

    expect(screen.getByText('标题')).toBeInTheDocument();
    expect(missingKeyWarnings).toEqual([]);
  });

  it('passes localized page labels through to SmartUpload fields', async () => {
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: ({
        componentName,
        props,
      }: {
        componentName: string;
        props: Record<string, unknown>;
      }) => {
        capturedPropsSpy({ componentName, props });
        return <div data-testid="component-loader">{componentName}</div>;
      },
    }));

    const { ControlledFieldRenderer } = await import('../ControlledFieldRenderer');

    render(
      <ControlledFieldRenderer
        field={
          {
            field: 'gerber_source_file',
            label: {
              'zh-CN': 'Gerber/PCB资料包',
              en: 'Gerber/PCB Package',
            },
            component: 'SmartUpload',
            dataType: 'file',
            props: {
              accept: '.zip,.rar,.7z',
              maxCount: 1,
            },
          } as any
        }
        value={undefined}
        onChange={vi.fn()}
        context={{ locale: 'zh-CN', t: (key: string) => key } as any}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('component-loader')).toHaveTextContent('SmartUpload');
    });

    expect(screen.getByText('Gerber/PCB资料包')).toBeInTheDocument();
    expect(capturedPropsSpy).toHaveBeenCalledTimes(1);
    expect(capturedPropsSpy.mock.calls[0]?.[0]?.props?.label).toBeUndefined();
  });

  it('disables dependent selects and suppresses their data source until the parent has a value', async () => {
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: ({
        componentName,
        props,
      }: {
        componentName: string;
        props: Record<string, unknown>;
      }) => {
        capturedPropsSpy({ componentName, props });
        return <div data-testid="component-loader">{componentName}</div>;
      },
    }));

    const { ControlledFieldRenderer } = await import('../ControlledFieldRenderer');

    render(
      <ControlledFieldRenderer
        field={
          {
            field: 'bom_task_project_id',
            component: 'SmartSelect',
            dependsOn: 'bom_task_customer_id',
            dataSource: {
              type: 'api',
              endpoint: '/api/dynamic/req_requirement_set_pcba_bom/list',
              method: 'get',
              params: {
                bom_project_customer_id: '${form.bom_task_customer_id}',
              },
              adaptor: 'optionList',
              valueField: 'pid',
              labelField: 'bom_project_name',
              autoFetch: false,
              dependOn: ['form.bom_task_customer_id'],
            },
          } as any
        }
        value={undefined}
        onChange={vi.fn()}
        context={{ locale: 'zh-CN', t: (key: string) => key, form: {} } as any}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('component-loader')).toHaveTextContent('SmartSelect');
    });

    const props = capturedPropsSpy.mock.calls[0]?.[0]?.props;
    expect(props?.disabled).toBe(true);
    expect(props?.dataSource).toBeUndefined();
  });

  it('keeps dependent select data sources active after the parent has a value', async () => {
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: ({
        componentName,
        props,
      }: {
        componentName: string;
        props: Record<string, unknown>;
      }) => {
        capturedPropsSpy({ componentName, props });
        return <div data-testid="component-loader">{componentName}</div>;
      },
    }));

    const { ControlledFieldRenderer } = await import('../ControlledFieldRenderer');
    const dataSource = {
      type: 'api',
      endpoint: '/api/dynamic/req_requirement_set_pcba_bom/list',
      method: 'get',
      params: {
        bom_project_customer_id: '${form.bom_task_customer_id}',
      },
      adaptor: 'optionList',
      valueField: 'pid',
      labelField: 'bom_project_name',
      autoFetch: false,
      dependOn: ['form.bom_task_customer_id'],
    };

    render(
      <ControlledFieldRenderer
        field={
          {
            field: 'bom_task_project_id',
            component: 'SmartSelect',
            dependsOn: 'bom_task_customer_id',
            dataSource,
          } as any
        }
        value={undefined}
        onChange={vi.fn()}
        context={
          {
            locale: 'zh-CN',
            t: (key: string) => key,
            form: { bom_task_customer_id: 'customer-1' },
          } as any
        }
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('component-loader')).toHaveTextContent('SmartSelect');
    });

    const props = capturedPropsSpy.mock.calls[0]?.[0]?.props;
    expect(props?.disabled).toBe(false);
    expect(props?.dataSource).toBe(dataSource);
  });

  it('routes sys_user reference fields to the system user search endpoint', async () => {
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: ({
        componentName,
        props,
      }: {
        componentName: string;
        props: Record<string, unknown>;
      }) => {
        capturedPropsSpy({ componentName, props });
        return <div data-testid="component-loader">{componentName}</div>;
      },
    }));

    const { ControlledFieldRenderer } = await import('../ControlledFieldRenderer');

    const field = {
      field: 'wd_req_cc_users',
      component: 'SmartSelect',
      props: {
        refTarget: {
          targetModel: 'sys_user',
          targetField: 'username',
        },
      },
      dataType: 'reference',
    } as any;

    render(
      <ControlledFieldRenderer
        field={field}
        value={undefined}
        onChange={vi.fn()}
        context={{ locale: 'zh-CN', t: (key: string) => key } as any}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('component-loader')).toHaveTextContent('SmartSelect');
    });

    expect(capturedPropsSpy).toHaveBeenCalledTimes(1);
    expect(capturedPropsSpy.mock.calls[0]?.[0]?.props?.dataSource).toEqual({
      type: 'api',
      modelCode: 'sys_user',
      endpoint: '/api/admin/users/search',
      method: 'get',
      params: { size: 200 },
      adaptor: 'optionList',
      valueField: 'pid',
      labelField: 'username',
      autoFetch: true,
    });
  });

  it('uses reference displayField labels and backend pageNum pagination params', async () => {
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: ({
        componentName,
        props,
      }: {
        componentName: string;
        props: Record<string, unknown>;
      }) => {
        capturedPropsSpy({ componentName, props });
        return <div data-testid="component-loader">{componentName}</div>;
      },
    }));

    const { ControlledFieldRenderer } = await import('../ControlledFieldRenderer');

    render(
      <ControlledFieldRenderer
        field={
          {
            field: 'crm_opp_account_id',
            component: 'SmartSelect',
            dataType: 'reference',
            props: {
              refTarget: {
                targetModel: 'crm_account',
                targetField: 'id',
                displayField: 'crm_acc_name',
              },
            },
          } as any
        }
        value={undefined}
        onChange={vi.fn()}
        context={{ locale: 'zh-CN', t: (key: string) => key } as any}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('component-loader')).toHaveTextContent('SmartSelect');
    });

    expect(capturedPropsSpy.mock.calls[0]?.[0]?.props?.dataSource).toEqual({
      type: 'api',
      modelCode: 'crm_account',
      endpoint: '/api/dynamic/crm_account/list',
      method: 'get',
      params: { pageNum: 1, pageSize: 200 },
      adaptor: 'optionList',
      valueField: 'pid',
      labelField: 'crm_acc_name',
      autoFetch: true,
    });
  });

  it('clamps large reference pageSize and passes reference sort params', async () => {
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: ({
        componentName,
        props,
      }: {
        componentName: string;
        props: Record<string, unknown>;
      }) => {
        capturedPropsSpy({ componentName, props });
        return <div data-testid="component-loader">{componentName}</div>;
      },
    }));

    const { ControlledFieldRenderer } = await import('../ControlledFieldRenderer');

    render(
      <ControlledFieldRenderer
        field={
          {
            field: 'bom_sp_manufacturer_part_id',
            component: 'SmartSelect',
            dataType: 'reference',
            props: {
              refTarget: {
                modelCode: 'bom_manufacturer_part',
                displayField: 'bom_mp_mpn',
                pageSize: 5000,
                sortField: 'created_at',
                sortOrder: 'desc',
              },
            },
          } as any
        }
        value={undefined}
        onChange={vi.fn()}
        context={{ locale: 'zh-CN', t: (key: string) => key } as any}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('component-loader')).toHaveTextContent('SmartSelect');
    });

    expect(capturedPropsSpy.mock.calls[0]?.[0]?.props?.dataSource).toEqual({
      type: 'api',
      modelCode: 'bom_manufacturer_part',
      endpoint: '/api/dynamic/bom_manufacturer_part/list',
      method: 'get',
      params: { pageNum: 1, pageSize: 500, sortField: 'created_at', sortOrder: 'desc' },
      adaptor: 'optionList',
      valueField: 'pid',
      labelField: 'bom_mp_mpn',
      autoFetch: true,
    });
  });

  it('wires permitted reference inline-create through controlled form fields', async () => {
    vi.resetModules();
    const onChange = vi.fn();
    const setFormFieldValue = vi.fn();
    const executeCommand = vi.fn();
    const manager = {
      getDataSourceIdsByModel: vi.fn(() => ['ds_customer_options']),
      getState: vi.fn(() => ({ data: [{ value: 'OLD', label: 'Old Customer' }] })),
      setData: vi.fn(),
      reload: vi.fn().mockResolvedValue(undefined),
    };
    const dialogProps: Record<string, any> = {};

    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: ({
        componentName,
        props,
      }: {
        componentName: string;
        props: Record<string, unknown>;
      }) => {
        capturedPropsSpy({ componentName, props });
        return (
          <button
            data-testid="open-create"
            onClick={() => (props.onCreateNew as (() => void) | undefined)?.()}
          >
            open
          </button>
        );
      },
    }));
    vi.doMock('~/contexts/AuthContext', () => ({
      usePermission: (code: string) => code === 'e2et.customer.manage',
    }));
    vi.doMock('~/framework/meta/hooks/useActionHandler', () => ({
      useActionHandler: () => ({ executeCommand }),
    }));
    vi.doMock('~/framework/meta/contexts/DataSourceContext', () => ({
      useDataSourceManagerOptional: () => manager,
    }));
    vi.doMock('~/framework/meta/runtime/reference-create/ReferenceCreateDialog', () => ({
      ReferenceCreateDialog: (props: Record<string, any>) => {
        Object.assign(dialogProps, props);
        return props.open ? (
          <button
            data-testid="complete-create"
            onClick={() => props.onCreated({ value: 'CUST-1', label: 'Acme' })}
          >
            complete
          </button>
        ) : null;
      },
    }));

    const { ControlledFieldRenderer } = await import('../ControlledFieldRenderer');

    render(
      <ControlledFieldRenderer
        field={
          {
            field: 'e2et_order_customer',
            component: 'SmartSelect',
            dataType: 'reference',
            allowCreate: true,
            createCommand: 'e2et:create_customer',
            createPageKey: 'e2et_customer_form',
            createPermission: 'e2et.customer.manage',
            refTarget: {
              targetModel: 'e2et_customer',
              targetField: 'e2et_cust_name',
            },
          } as any
        }
        value={undefined}
        onChange={onChange}
        context={
          {
            locale: 'zh-CN',
            t: (key: string) => key,
            __setFormFieldValue: setFormFieldValue,
          } as any
        }
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('open-create')).toBeInTheDocument();
    });

    const props = capturedPropsSpy.mock.calls[0]?.[0]?.props;
    expect(props?.canCreateNew).toBe(true);
    expect(props?.dataSource).toMatchObject({
      modelCode: 'e2et_customer',
      endpoint: '/api/dynamic/e2et_customer/list',
      labelField: 'e2et_cust_name',
    });

    act(() => {
      (props?.onCreateNew as () => void)();
    });
    fireEvent.click(screen.getByTestId('complete-create'));

    expect(dialogProps).toMatchObject({
      targetModel: 'e2et_customer',
      createPageKey: 'e2et_customer_form',
      createCommand: 'e2et:create_customer',
      displayField: 'e2et_cust_name',
      executeCommand,
    });
    expect(onChange).toHaveBeenCalledWith('CUST-1');
    expect(setFormFieldValue).toHaveBeenCalledWith('e2et_order_customer', 'CUST-1');
    expect(manager.getDataSourceIdsByModel).toHaveBeenCalledWith('e2et_customer');
    expect(manager.setData).toHaveBeenCalledWith('ds_customer_options', [
      { value: 'CUST-1', label: 'Acme' },
      { value: 'OLD', label: 'Old Customer' },
    ]);
    expect(manager.reload).toHaveBeenCalledWith(['ds_customer_options']);
  });

  it('rehydrates stringified daterange values for edit mode', async () => {
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: ({
        componentName,
        props,
      }: {
        componentName: string;
        props: Record<string, unknown>;
      }) => {
        capturedPropsSpy({ componentName, props });
        return <div data-testid="component-loader">{componentName}</div>;
      },
    }));

    const { ControlledFieldRenderer } = await import('../ControlledFieldRenderer');

    render(
      <ControlledFieldRenderer
        field={{ field: 'sc_date_range', component: 'daterange', props: {} } as any}
        value={'{"start":"2026-04-24","end":"2026-04-25"}'}
        onChange={vi.fn()}
        context={{ locale: 'zh-CN', t: (key: string) => key } as any}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('component-loader')).toHaveTextContent('daterange');
    });

    expect(capturedPropsSpy.mock.calls[0]?.[0]?.props?.value).toEqual({
      start: '2026-04-24',
      end: '2026-04-25',
    });
  });

  it('serializes object outputs for json-like smart components and rehydrates inferred upload values', async () => {
    vi.resetModules();
    vi.doMock('~/framework/meta/rendering/components/ComponentLoader', () => ({
      ComponentLoader: ({
        componentName,
        props,
      }: {
        componentName: string;
        props: Record<string, unknown>;
      }) => {
        capturedPropsSpy({ componentName, props });
        return <div data-testid={`component-loader-${componentName}`}>{componentName}</div>;
      },
    }));

    const { ControlledFieldRenderer } = await import('../ControlledFieldRenderer');
    const onChange = vi.fn();

    const { rerender } = render(
      <ControlledFieldRenderer
        field={{ field: 'sc_working_hours', component: 'timerangepicker', props: {} } as any}
        value={'{"start":"09:00","end":"18:00"}'}
        onChange={onChange}
        context={{ locale: 'zh-CN', t: (key: string) => key } as any}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('component-loader-timerangepicker')).toHaveTextContent(
        'timerangepicker',
      );
    });
    const timerangepickerProps = capturedPropsSpy.mock.calls[0]?.[0]?.props;
    expect(timerangepickerProps?.value).toEqual({ start: '09:00', end: '18:00' });
    (timerangepickerProps?.onChange as (value: unknown) => void)?.({
      start: '10:00',
      end: '19:00',
    });
    expect(onChange).toHaveBeenCalledWith('{"start":"10:00","end":"19:00"}');

    capturedPropsSpy.mockClear();
    rerender(
      <ControlledFieldRenderer
        field={
          {
            field: 'sc_attachment_file',
            component: 'SmartUpload',
            dataType: 'file',
            props: {},
          } as any
        }
        value={'[{"name":"audit.txt","url":"/files/audit.txt","fileId":"f1"}]'}
        onChange={vi.fn()}
        context={{ locale: 'zh-CN', t: (key: string) => key } as any}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('component-loader-SmartUpload')).toHaveTextContent('SmartUpload');
    });
    const uploadProps = capturedPropsSpy.mock.calls[0]?.[0]?.props;
    expect(Array.isArray(uploadProps?.value)).toBe(true);
    expect(uploadProps?.value).toMatchObject([
      expect.objectContaining({
        name: 'audit.txt',
        status: 'done',
        url: '/files/audit.txt',
      }),
    ]);
  });
});
