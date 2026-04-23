import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const capturedPropsSpy = vi.fn();

describe('ControlledFieldRenderer', () => {
  beforeEach(() => {
    capturedPropsSpy.mockClear();
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
      endpoint: '/api/admin/users/search',
      method: 'get',
      params: { size: 200 },
      adaptor: 'optionList',
      valueField: 'pid',
      labelField: 'username',
      autoFetch: true,
    });
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
        field={{ field: 'sc_attachment_file', component: 'SmartUpload', dataType: 'file', props: {} } as any}
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
