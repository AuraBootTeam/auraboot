import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { DataSourceManager } from '../../../runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '../../../runtime/expression/context';
import { ExpressionEvaluator } from '../../../runtime/expression/evaluator';
import { businessFunctions } from '../../../runtime/expression/business-functions';
import { ScopedStateManager } from '../../../runtime/state/scoped-state';
import { fetchResult } from '~/shared/services/http-client';
import { FormButtonsBlockRenderer } from '../FormButtonsBlockRenderer';
import { useRuntimeStateSubscription, useDataSourceSubscription } from '../workbenchBlockUtils';
import { blockSchema } from '../../../validation/schemas/block.schema';
import { CardGridBlockRenderer } from '../CardGridBlockRenderer';
vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({
  useActionHandler: () => ({ handleAction: vi.fn() }),
}));
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'local' }),
  usePermission: () => true,
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showErrorToast: vi.fn(),
    showSuccessToast: vi.fn(),
    showWarningToast: vi.fn(),
    showInfoToast: vi.fn(),
  }),
}));
vi.mock('~/framework/meta/rendering/pages/hooks/useDictCache', () => ({
  useDictCache: () => ({ loaded: true, getDictLabel: () => undefined }),
}));
vi.mock('../../components/ComponentLoader', () => ({
  ComponentLoader: ({ props }: any) => (
    <label>
      {props.label}
      <input
        aria-label={props.label}
        value={props.value ?? ''}
        readOnly={props.readOnly}
        disabled={props.disabled}
        onChange={(event) => props.onChange(event.target.value)}
      />
    </label>
  ),
}));

describe('CardGrid completed command arrays (hermetic mounted DOM)', () => {
  it('uses POST array transport, retains exact dirty draft through refresh, and renders typed403', async () => {
    const state = new ScopedStateManager({} as any);
    state.createScope('edit');
    const manager = new DataSourceManager(createExpressionContext({ form: {}, state: {} } as any));
    manager.register('lines', {
      type: 'api',
      method: 'post',
      endpoint: '/api/meta/commands/execute/example:view',
      autoFetch: false,
      adaptor: 'commandArray',
      dataPath: 'lines',
      rowProjection: {pid:'pid',name:'name',quantity:'quantity', taxIncluded:{paths:'taxIncluded',optional:true,omitWhen:['undefined','null','empty']}},
      rowIdentityField:'pid',
      params: { targetRecordPid: 'p' },
    });
    const runtime = {
      getContext: () => ({
        fn: businessFunctions,
        locale: 'en',
        t: (key: string) => key,
        ...state.getScope('edit'),
      }),
      getEvaluator: () => new ExpressionEvaluator(),
      getStateManager: () => state,
      getScopeId: () => 'edit',
      getSchema: () => ({ id: 'edit' }),
      getDataSourceManager: () => manager,
      triggerFieldLinkage: vi.fn(),
    } as any;
    const completed = (qty: string) => ({
      code: '0',
      data: {
        phaseReached: 'completed',
        data: { lines: [{ pid: 'p', name: 'Item', quantity: qty, taxIncluded:false, richDTO:{secret:'not-editable'} }] },
      },
    });
    vi.mocked(fetchResult).mockResolvedValueOnce(completed('100.000000000000001') as any);
    await manager.fetch('lines');
    render(
      <MemoryRouter>
        <CardGridBlockRenderer
          runtime={runtime}
          block={
            {
              id: 'lines',
              blockType: 'card-grid',
              dataSource: 'lines',
              field: 'choices',
              titleField: 'name',
              fields: [{ field: 'quantity', label: 'Quantity', component: 'SmartInput' }],
            } as any
          }
        />
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(screen.getByLabelText('Quantity')).toHaveValue('100.000000000000001'),
    );
    expect(state.getFieldValue('edit','choices')).toEqual([{pid:'p',name:'Item',quantity:'100.000000000000001',taxIncluded:false}]);
    fireEvent.change(screen.getByLabelText('Quantity'), {
      target: { value: '60.000000000000001' },
    });
    vi.mocked(fetchResult).mockResolvedValueOnce(completed('999') as any);
    await act(async () => {
      await manager.reload('lines');
    });
    expect(screen.getByLabelText('Quantity')).toHaveValue('60.000000000000001');
    expect(
      businessFunctions.PROJECT_ROWS(
        state.getFieldValue('edit', 'choices'),
        { pid: 'pid', quantity: 'quantity' },
        'pid',
      ),
    ).toEqual([{ pid: 'p', quantity: '60.000000000000001' }]);
    vi.mocked(fetchResult).mockResolvedValueOnce({
      code: '403',
      message: 'Access forbidden',
      data: null,
    } as any);
    await act(async () => {
      await manager.reload('lines');
    });
    expect((manager.getState('lines')?.error as any).status).toBe(403);
    expect(screen.queryByText('Access forbidden')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Quantity')).not.toBeInTheDocument();
    expect(state.getFieldValue('edit', 'choices')[0].quantity).toBe('60.000000000000001');
    expect(vi.mocked(fetchResult).mock.calls.every((call) => call[1]?.method === 'post')).toBe(
      true,
    );
  });
});

describe('Declarative CardGrid selection (actual schema→renderer→projection)', () => {
  const rule={identityField:'orderLinePid',selectedField:'selected',positiveField:'remainingQty'};
  const enable="${fn.COUNT(fn.SELECT_ROWS(form.shipmentDraftLines,{identityField:'orderLinePid',selectedField:'selected',positiveField:'remainingQty'},dataSources.shipmentDraftRows)) > 0}";
  const expression="${fn.PROJECT_ROWS(fn.SELECT_ROWS(form.shipmentDraftLines,{identityField:'orderLinePid',selectedField:'selected',positiveField:'remainingQty'},dataSources.shipmentDraftRows),{orderLinePid:'orderLinePid',quantity:'quantity'},'orderLinePid')}";
  async function fixture(){
    const state=new ScopedStateManager({locale:'en',theme:'light'} as any);state.createScope('s');
    const manager=new DataSourceManager(createExpressionContext({} as any));
    manager.register('shipmentDraftRows',{type:'api',endpoint:'/api/example/read-owned-lines',method:'post',autoFetch:false,adaptor:'commandArray',dataPath:'lines',rowIdentityField:'orderLinePid',rowProjection:{orderLinePid:'pid',materialName:'name',quantity:'remainingQty',remainingQty:'remainingQty'}});
    const completed=(remaining:string)=>({code:'0',data:{phaseReached:'completed',data:{lines:[{pid:'p',name:'Positive',remainingQty:remaining},{pid:'z',name:'Zero',remainingQty:'0.0000'}]}}});
    vi.mocked(fetchResult).mockResolvedValueOnce(completed('10.000000000000001') as any);await manager.fetch('shipmentDraftRows');
    const evaluator=new ExpressionEvaluator();
    const runtime={getContext:()=>({...state.getContext('s'),fn:businessFunctions,locale:'en',t:(key:string)=>key,dataSources:{shipmentDraftRows:manager.getData('shipmentDraftRows')}}),getEvaluator:()=>evaluator,getStateManager:()=>state,getScopeId:()=> 's',getSchema:()=>({id:'s'}),getDataSourceManager:()=>manager,triggerFieldLinkage:vi.fn()} as any;
    const block=blockSchema.parse({id:'s',blockType:'card-grid',dataSource:'shipmentDraftRows',field:'shipmentDraftLines',titleField:'materialName',rowSelection:{...rule,defaultSelected:true,label:'Select',emptyText:'Choose at least one row'},fields:[{field:'quantity',label:'Quantity',component:'SmartInput'},{field:'remainingQty',label:'Remaining',component:'SmartInput',readOnly:true}]});
    function View(){useRuntimeStateSubscription(runtime);useDataSourceSubscription(runtime,'shipmentDraftRows');return <><CardGridBlockRenderer block={block as any} runtime={runtime}/><FormButtonsBlockRenderer block={{id:'send',blockType:'form-buttons',buttons:[{code:'submit',content:'Submit',enableWhen:enable,commandCode:'example:send'}]} as any} runtime={runtime}/></>;}
    render(<MemoryRouter><View/></MemoryRouter>);
    await waitFor(()=>expect(state.getFieldValue('s','shipmentDraftLines')).toHaveLength(2));
    return {state,manager,runtime,evaluator,completed,payload:()=>evaluator.evaluate(expression,runtime.getContext())};
  }
  it('defaults only positive rows, keeps zero readonly/unselected, sends only projected selected identities',async()=>{
    const {state,payload}=await fixture();
    expect(screen.getByRole('checkbox',{name:'Select Positive'})).toBeChecked();
    expect(screen.getByRole('checkbox',{name:'Select Zero'})).not.toBeChecked();
    expect(screen.getByRole('checkbox',{name:'Select Zero'})).toBeDisabled();
    expect(screen.getAllByLabelText('Quantity')[1]).toHaveAttribute('readonly');
    fireEvent.change(screen.getAllByLabelText('Quantity')[1],{target:{value:'999'}});
    expect(state.getFieldValue('s','shipmentDraftLines')[1].quantity).toBe('0.0000');
    expect(payload()).toEqual([{orderLinePid:'p',quantity:'10.000000000000001'}]);
    expect(screen.getByTestId('form-btn-submit')).not.toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox',{name:'Select Positive'}));
    expect(payload()).toEqual([]);expect(screen.getByTestId('form-btn-submit')).toBeDisabled();expect(screen.getByRole('status')).toHaveTextContent('Choose at least one row');
    expect(vi.mocked(fetchResult).mock.calls.at(-1)?.[1]?.method).toBe('post');
  });
  it('refresh preserves dirty selection/quantity but current zero cannot be reselected or submitted',async()=>{
    const {state,manager,completed,payload}=await fixture();
    fireEvent.change(screen.getAllByLabelText('Quantity')[0],{target:{value:'5.000000000000001'}});
    vi.mocked(fetchResult).mockResolvedValueOnce(completed('0') as any);
    await act(async()=>{await manager.reload('shipmentDraftRows');});
    expect(state.getFieldValue('s','shipmentDraftLines')[0].quantity).toBe('5.000000000000001');
    expect(state.getFieldValue('s','shipmentDraftLines')[0].selected).toBe(true);
    expect(screen.getAllByLabelText('Quantity')[0]).toHaveAttribute('readonly');
    expect(screen.getAllByLabelText('Remaining')[0]).toHaveValue('0');
    expect(payload()).toBeUndefined();expect(screen.getByTestId('form-btn-submit')).toBeDisabled();
    expect(()=>businessFunctions.SELECT_ROWS(state.getFieldValue('s','shipmentDraftLines'),rule,manager.getData('shipmentDraftRows'))).toThrow('availability changed');
    fireEvent.click(screen.getByRole('checkbox',{name:'Select Positive'}));
    expect(screen.getByRole('checkbox',{name:'Select Positive'})).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox',{name:'Select Positive'}));
    expect(state.getFieldValue('s','shipmentDraftLines')[0].selected).toBe(false);
    expect(payload()).toEqual([]);
  });
  it('keeps an explicitly unselected row and edit through a same-fact refresh; rejects forged zero selection',async()=>{
    const {state,manager,completed,payload}=await fixture();
    fireEvent.change(screen.getAllByLabelText('Quantity')[0],{target:{value:'2.000000000000001'}});
    fireEvent.click(screen.getByRole('checkbox',{name:'Select Positive'}));
    vi.mocked(fetchResult).mockResolvedValueOnce(completed('10.000000000000001') as any);
    await act(async()=>{await manager.reload('shipmentDraftRows');});
    expect(screen.getAllByLabelText('Quantity')[0]).toHaveValue('2.000000000000001');
    expect(screen.getByRole('checkbox',{name:'Select Positive'})).not.toBeChecked();
    expect(payload()).toEqual([]);
    const form=state.getFieldValue('s','shipmentDraftLines');
    state.updateField('s','shipmentDraftLines',form.map((row:any)=>row.orderLinePid==='z'?{...row,selected:true}:row));
    expect(payload()).toBeUndefined();expect(screen.getByTestId('form-btn-submit')).toBeDisabled();
    expect(()=>businessFunctions.SELECT_ROWS(state.getFieldValue('s','shipmentDraftLines'),rule,manager.getData('shipmentDraftRows'))).toThrow('availability changed');
  });
});
