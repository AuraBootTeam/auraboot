import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Select, decodeBooleanChoice } from '../Select';

let mockOptions: Array<{ label: string; value: string }> = [];
let mockLoading = false;
let mockRefetch = vi.fn();

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router');
  return {
    ...actual,
    useActionData: () => null,
  };
});

vi.mock('~/framework/meta/hooks/useFieldDataSource', () => ({
  useFieldDataSource: () => ({
    options: mockOptions,
    loading: mockLoading,
    error: null,
    refetch: mockRefetch,
  }),
}));

describe('Smart Select', () => {
  beforeEach(() => {
    mockOptions = [];
    mockLoading = false;
    mockRefetch = vi.fn();
  });

  it('exposes an external field label on the interactive select trigger', () => {
    render(<div><label htmlFor="rfq_state">RFQ state</label>
      <Select name="rfq_state" aria-label="RFQ state"
        options={[{ label: 'Draft', value: 'draft' }]} /></div>);
    const trigger = screen.getByRole('combobox', { name: 'RFQ state' });
    expect(screen.getByLabelText('RFQ state')).toBe(trigger);
    expect(trigger).toHaveAttribute('id', 'rfq_state');
  });

  it('names the interactive trigger from its own localized label', () => {
    render(<Select name="source_type" label="Source type"
      options={[{ label: 'FQC', value: 'fqc' }]} />);
    expect(screen.getByRole('combobox', { name: 'Source type' })).toBeInTheDocument();
  });

  it('provides a keyboard-accessible named clear action without changing the clear value contract', () => {
    const onChange = vi.fn();
    const onClear = vi.fn();
    render(<Select name="source_type" label="Source type" value="fqc"
      options={[{ label: 'FQC', value: 'fqc' }]} clearable onChange={onChange} onClear={onClear} />);
    const clear = screen.getByRole('button', { name: /Source type/ });
    expect(clear.tabIndex).toBe(0);
    fireEvent.click(clear);
    expect(onChange).toHaveBeenCalledWith('');
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  it('names the clear action from an external wrapper accessible label', () => {
    render(<Select name="source_type" aria-label="来源类型" value="fqc"
      options={[{ label: 'FQC', value: 'fqc' }]} clearable />);
    expect(screen.getByRole('button', { name: '清空来源类型' })).toBeInTheDocument();
  });

  it('does not expose a clear action for a read-only selection', () => {
    render(<Select name="source_type" label="Source type" value="fqc"
      options={[{ label: 'FQC', value: 'fqc' }]} clearable readOnly />);
    expect(screen.queryByRole('button', { name: /Source type/ })).not.toBeInTheDocument();
  });

  it('shows the selected option label after async options load without requiring reselection', () => {
    const { rerender } = render(
      <Select
        name="wd_req_type"
        value="annual"
        dataSource={{
          type: 'api',
          endpoint: '/api/meta/dict/by-code/wd_leave_type/data',
          method: 'get',
          adaptor: 'dictData',
        } as any}
      />,
    );

    const trigger = screen.getByTestId('select-trigger-wd_req_type');
    expect(trigger).not.toHaveTextContent('年假');

    mockOptions = [{ label: '年假', value: 'annual' }];
    rerender(
      <Select
        name="wd_req_type"
        value="annual"
        dataSource={{
          type: 'api',
          endpoint: '/api/meta/dict/by-code/wd_leave_type/data',
          method: 'get',
          adaptor: 'dictData',
        } as any}
      />,
    );

    expect(screen.getByTestId('select-trigger-wd_req_type')).toHaveTextContent('年假');
  });

  it('keeps the Radix select controlled when an empty value later becomes populated', () => {
    mockOptions = [
      { label: 'Queued', value: 'QUEUED' },
      { label: 'Running', value: 'RUNNING' },
    ];
    vi.mocked(console.error).mockClear();

    const { rerender } = render(<Select name="crawler_status" />);

    rerender(<Select name="crawler_status" value="RUNNING" />);

    const consoleErrors = vi
      .mocked(console.error)
      .mock.calls.map((call) => call.map(String).join(' '))
      .join('\n');
    expect(consoleErrors).not.toContain('changing from uncontrolled to controlled');
  });

  it('refetches async options when opening a single select', () => {
    render(
      <Select
        name="bom_project_id"
        dataSource={{
          type: 'api',
          endpoint: '/api/dynamic/req_requirement_set_pcba_bom/list',
          method: 'get',
          autoFetch: false,
        } as any}
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));

    expect(mockRefetch).toHaveBeenCalledTimes(1);
  });

  it('filters single-select options with an in-dropdown search input while keeping create available', () => {
    mockOptions = [
      { label: 'Alpha Project', value: 'alpha' },
      { label: 'Beta Project', value: 'beta' },
    ];

    render(
      <Select
        name="bom_project_id"
        dataSource={{
          type: 'api',
          endpoint: '/api/dynamic/bom_project/list',
          method: 'get',
        } as any}
        canCreateNew
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));
    fireEvent.change(screen.getByTestId('select-search-bom_project_id'), {
      target: { value: 'beta' },
    });

    expect(screen.queryByText('Alpha Project')).not.toBeInTheDocument();
    expect(screen.getByText('Beta Project')).toBeInTheDocument();
    expect(screen.getByTestId('select-create-new-bom_project_id')).toBeInTheDocument();
  });

  it('does not render an empty dropdown panel when there are no options', () => {
    render(
      <Select
        name="bom_project_id"
        label="所属项目"
        value=""
        options={[]}
        validationRules={[{ type: 'required', message: '此字段为必填项' }]}
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('does not render a loading-only dropdown panel before async options arrive', () => {
    mockLoading = true;

    render(
      <Select
        name="bom_project_id"
        label="所属项目"
        value=""
        dataSource={{
          type: 'api',
          endpoint: '/api/dynamic/req_requirement_set_pcba_bom/list',
          method: 'get',
          autoFetch: false,
        } as any}
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('does not open or refetch a dependent select when the dependency value is empty', () => {
    mockOptions = [{ label: '2', value: 'project-2' }];

    render(
      <Select
        name="bom_project_id"
        label="所属项目"
        value=""
        dataSource={
          {
            type: 'api',
            endpoint: '/api/dynamic/req_requirement_set_pcba_bom/list',
            method: 'get',
            autoFetch: false,
            dependOn: ['form.bom_task_customer_id'],
          } as any
        }
        context={{ form: { bom_task_customer_id: '' } } as any}
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));

    expect(mockRefetch).not.toHaveBeenCalled();
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });
});

describe('Explicit boolean Select (no business default)', () => {
  const booleanOptions = [{value:true,label:'Yes'},{value:false,label:'No'}];
  it('keeps unset distinct from explicit false and clears back to unset', () => {
    mockOptions=booleanOptions as any;const onChange=vi.fn();
    const {rerender}=render(<Select name="tax" label="Tax" valueType="boolean" placeholder="Unset" options={booleanOptions} clearable onChange={onChange}/>);
    expect(screen.getByRole('combobox',{name:'Tax'})).toHaveTextContent('Unset');
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('combobox',{name:'Tax'}));
    fireEvent.click(screen.getByRole('option',{name:'No'}));
    expect(onChange).toHaveBeenLastCalledWith(false);
    rerender(<Select name="tax" label="Tax" valueType="boolean" value={false} options={booleanOptions} clearable onChange={onChange}/>);
    expect(screen.getByRole('combobox',{name:'Tax'})).toHaveTextContent('No');
    fireEvent.click(screen.getByRole('button',{name:/Tax/}));
    expect(onChange).toHaveBeenLastCalledWith(null);
  });
  it.each([
    {options:[{value:'false',label:'No'}]}, {options:[{value:true,label:'A'},{value:true,label:'B'}]},
    {options:[{value:null,label:'Unset'}]},
  ])('rejects ambiguous or string boolean declarations', ({options}) => {
    expect(()=>render(<Select name="bad" valueType="boolean" options={options as any}/>)).toThrow();
  });
  it('rejects unmatched current values, multiple and dynamic choices without widening ordinary select behavior', () => {
    expect(()=>render(<Select name="bad" valueType="boolean" value="false" options={booleanOptions}/>)).toThrow();
    expect(()=>render(<Select name="bad" valueType="boolean" value={true} options={[{value:false,label:'No'}]}/>)).toThrow();
    expect(()=>render(<Select name="bad" valueType="boolean" multiple options={booleanOptions}/>)).toThrow();
    expect(()=>render(<Select name="bad" valueType="boolean" dataSource={{type:'static',data:booleanOptions}} options={booleanOptions}/>)).toThrow();
  });
});

describe('Typed boolean choice transport guard', () => {
  it('refuses disabled/unmatched choices and never interprets string false options as boolean', () => {
    expect(()=>decodeBooleanChoice('false',[{value:false,label:'No',disabled:true}])).toThrow();
    expect(()=>decodeBooleanChoice('false',[{value:'false',label:'No'}])).toThrow();
    expect(()=>decodeBooleanChoice('missing',[{value:false,label:'No'}])).toThrow();
    expect(decodeBooleanChoice('false',[{value:false,label:'No'}])).toBe(false);
  });
});
