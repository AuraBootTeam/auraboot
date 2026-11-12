import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Select, decodeBooleanChoice } from '../Select';

let mockOptions: Array<{ label: string; value: string }> = [];
let mockLoading = false;
let mockError: string | null = null;
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
    error: mockError,
    refetch: mockRefetch,
  }),
}));

describe('Smart Select', () => {
  beforeEach(() => {
    mockOptions = [];
    mockLoading = false;
    mockError = null;
    mockRefetch = vi.fn();
  });

  it('exposes an external field label on the interactive select trigger', () => {
    render(
      <div>
        <label htmlFor="rfq_state">RFQ state</label>
        <Select
          name="rfq_state"
          aria-label="RFQ state"
          options={[{ label: 'Draft', value: 'draft' }]}
        />
      </div>,
    );
    const trigger = screen.getByRole('combobox', { name: 'RFQ state' });
    expect(screen.getByLabelText('RFQ state')).toBe(trigger);
    expect(trigger).toHaveAttribute('id', 'rfq_state');
  });

  it('names the interactive trigger from its own localized label', () => {
    render(
      <Select name="source_type" label="Source type" options={[{ label: 'FQC', value: 'fqc' }]} />,
    );
    expect(screen.getByRole('combobox', { name: 'Source type' })).toBeInTheDocument();
  });

  it('provides a keyboard-accessible named clear action without changing the clear value contract', () => {
    const onChange = vi.fn();
    const onClear = vi.fn();
    render(
      <Select
        name="source_type"
        label="Source type"
        value="fqc"
        options={[{ label: 'FQC', value: 'fqc' }]}
        clearable
        onChange={onChange}
        onClear={onClear}
      />,
    );
    const clear = screen.getByRole('button', { name: /Source type/ });
    expect(clear.tabIndex).toBe(0);
    fireEvent.click(clear);
    expect(onChange).toHaveBeenCalledWith('');
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  it('names the clear action from an external wrapper accessible label', () => {
    render(
      <Select
        name="source_type"
        aria-label="来源类型"
        value="fqc"
        options={[{ label: 'FQC', value: 'fqc' }]}
        clearable
      />,
    );
    expect(screen.getByRole('button', { name: '清空来源类型' })).toBeInTheDocument();
  });

  it('does not expose a clear action for a read-only selection', () => {
    render(
      <Select
        name="source_type"
        label="Source type"
        value="fqc"
        options={[{ label: 'FQC', value: 'fqc' }]}
        clearable
        readOnly
      />,
    );
    expect(screen.queryByRole('button', { name: /Source type/ })).not.toBeInTheDocument();
  });

  it('shows the selected option label after async options load without requiring reselection', () => {
    const { rerender } = render(
      <Select
        name="wd_req_type"
        value="annual"
        dataSource={
          {
            type: 'api',
            endpoint: '/api/meta/dict/by-code/wd_leave_type/data',
            method: 'get',
            adaptor: 'dictData',
          } as any
        }
      />,
    );

    const trigger = screen.getByTestId('select-trigger-wd_req_type');
    expect(trigger).not.toHaveValue('年假');

    mockOptions = [{ label: '年假', value: 'annual' }];
    rerender(
      <Select
        name="wd_req_type"
        value="annual"
        dataSource={
          {
            type: 'api',
            endpoint: '/api/meta/dict/by-code/wd_leave_type/data',
            method: 'get',
            adaptor: 'dictData',
          } as any
        }
      />,
    );

    expect(screen.getByTestId('select-trigger-wd_req_type')).toHaveValue('年假');
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
        dataSource={
          {
            type: 'api',
            endpoint: '/api/dynamic/req_requirement_set_pcba_bom/list',
            method: 'get',
            autoFetch: false,
          } as any
        }
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));

    expect(mockRefetch).toHaveBeenCalledTimes(1);
  });

  it('filters options by typing in the select itself while keeping create available', () => {
    mockOptions = [
      { label: 'Alpha Project', value: 'alpha' },
      { label: 'Beta Project', value: 'beta' },
    ];

    render(
      <Select
        name="bom_project_id"
        dataSource={
          {
            type: 'api',
            endpoint: '/api/dynamic/bom_project/list',
            method: 'get',
          } as any
        }
        canCreateNew
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));
    fireEvent.change(screen.getByTestId('select-trigger-bom_project_id'), {
      target: { value: 'beta' },
    });

    expect(screen.queryByText('Alpha Project')).not.toBeInTheDocument();
    expect(screen.getByText('Beta Project')).toBeInTheDocument();
    expect(screen.getByTestId('select-create-new-bom_project_id')).toBeInTheDocument();
  });

  it('matches partial Chinese names and codes without committing the query', () => {
    mockOptions = [
      { label: 'C0237-新光维', value: 'customer-1' },
      { label: 'C0246-歌风智能', value: 'customer-2' },
    ];
    const onChange = vi.fn();
    render(<Select name="customer" label="客户" onChange={onChange} />);
    const input = screen.getByRole('combobox', { name: '客户' });
    fireEvent.click(input);
    expect(screen.getAllByRole('combobox')).toHaveLength(1);
    expect(document.querySelectorAll('input[type="text"]')).toHaveLength(1);
    fireEvent.change(input, { target: { value: '光维' } });
    expect(screen.getByRole('option', { name: 'C0237-新光维' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'C0246-歌风智能' })).not.toBeInTheDocument();
    fireEvent.change(input, { target: { value: '  c024  ' } });
    expect(screen.getByRole('option', { name: 'C0246-歌风智能' })).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
    expect(document.querySelector('input[type="hidden"]')).toHaveValue('');
    fireEvent.click(screen.getByRole('option', { name: 'C0246-歌风智能' }));
    expect(onChange).toHaveBeenLastCalledWith('customer-2');
    expect(input).toHaveValue('C0246-歌风智能');
  });

  it('keeps the committed ID and restores its label on Escape, blur and Tab', () => {
    mockOptions = [{ label: 'Acme', value: 'customer-1' }];
    const onChange = vi.fn();
    render(<Select name="customer" label="Customer" value="customer-1" onChange={onChange} />);
    const input = screen.getByRole('combobox');
    for (const key of ['Escape', 'Tab', 'blur']) {
      fireEvent.click(input);
      fireEvent.change(input, { target: { value: 'unmatched' } });
      if (key === 'blur') fireEvent.blur(input);
      else fireEvent.keyDown(input, { key });
      expect(input).toHaveValue('Acme');
      expect(input).toHaveAttribute('aria-expanded', 'false');
      expect(document.querySelector('input[type="hidden"]')).toHaveValue('customer-1');
    }
    expect(onChange).not.toHaveBeenCalled();
  });

  it('navigates enabled matches with arrows and commits only an explicit keyboard choice', () => {
    mockOptions = [
      { label: 'Acme blocked', value: 'blocked', disabled: true } as any,
      { label: 'Acme enabled', value: 'enabled' },
    ];
    const onChange = vi.fn();
    render(<Select name="customer" onChange={onChange} />);
    const input = screen.getByRole('combobox');
    fireEvent.change(input, { target: { value: 'acme' } });
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true, keyCode: 229 });
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    const activeId = input.getAttribute('aria-activedescendant');
    expect(document.getElementById(activeId!)).toHaveTextContent('Acme enabled');
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).toHaveBeenLastCalledWith('enabled');
    expect(input).toHaveValue('Acme enabled');
  });

  it('closes a dependent picker and discards its query when the parent is cleared', () => {
    mockOptions = [{ label: 'Project', value: 'project-1' }];
    const dataSource = { type: 'api', endpoint: '/projects', dependOn: ['form.customer'] } as any;
    const { rerender } = render(
      <Select
        name="project"
        dataSource={dataSource}
        context={{ form: { customer: 'c1' } } as any}
      />,
    );
    const input = screen.getByRole('combobox');
    fireEvent.click(input);
    fireEvent.change(input, { target: { value: 'project' } });
    rerender(
      <Select name="project" dataSource={dataSource} context={{ form: { customer: '' } } as any} />,
    );
    expect(input).toBeDisabled();
    expect(input).toHaveValue('');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('shows an explicit empty state when there are no options', () => {
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

    expect(screen.getByRole('status')).toHaveTextContent('无匹配结果');
  });

  it('shows loading feedback while async options arrive', () => {
    mockLoading = true;

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
          } as any
        }
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));

    expect(screen.getByRole('status')).toHaveTextContent('加载中');
  });

  it('replaces stale candidates with an error state and allows an explicit reopen', () => {
    mockOptions = [{ label: 'Acme', value: 'acme' }];
    mockError = 'Network unavailable';
    render(<Select name="customer" dataSource={{ type: 'api', endpoint: '/customers' } as any} />);
    const input = screen.getByRole('combobox');
    fireEvent.click(input);
    expect(screen.getByRole('status')).toHaveTextContent('加载失败');
    expect(screen.queryByRole('option')).not.toBeInTheDocument();
    fireEvent.keyDown(input, { key: 'Escape' });
    fireEvent.click(input);
    expect(mockRefetch).toHaveBeenCalledTimes(2);
  });

  it('keeps read-only labels visible and prevents opening or refetching', () => {
    mockOptions = [{ label: 'Acme', value: 'acme' }];
    render(
      <Select
        name="customer"
        value="acme"
        readOnly
        dataSource={{ type: 'api', endpoint: '/customers' } as any}
      />,
    );
    const input = screen.getByRole('combobox');
    expect(input).toHaveValue('Acme');
    expect(input).toBeDisabled();
    fireEvent.click(input);
    expect(mockRefetch).not.toHaveBeenCalled();
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
