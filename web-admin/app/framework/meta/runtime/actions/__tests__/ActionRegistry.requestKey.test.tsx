import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { FormButtonsBlockRenderer } from '../../../rendering/blocks/FormButtonsBlockRenderer';
import { actionRegistry } from '../ActionRegistry';
import { ExpressionEvaluator } from '../../expression/evaluator';
import { ScopedStateManager } from '../../state/scoped-state';
import { FlowRunner } from '../../schema-runtime/FlowRunner';
const transport = vi.hoisted(() => vi.fn());
vi.mock('~/shared/services/http-client', () => ({ fetchResult: transport }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({token:'fixture',hasPermission:()=>true}) }));

describe('Mounted schema buttons use stable scoped domain request keys', () => {
  function fixture(initial?: unknown) {
    const state = new ScopedStateManager({locale:'en',theme:'light'} as any);
    state.createScope('ship',{form:{quantity:'250.0000'},state:{shipmentRequestKey:initial}});
    const evaluator = new ExpressionEvaluator();
    const loadData = vi.fn().mockResolvedValue(undefined);
    const toast = vi.fn();
    const flow = new FlowRunner({evaluator,actionRegistry,stateManager:state,scopeId:'ship',dataSourceManager:{} as any,schema:{} as any,getAllFormFields:()=>[]});
    const runtime = {getContext:()=>({...state.getContext('ship'),t:(s:string)=>s,loadData}),getEvaluator:()=>evaluator,
      getSchema:()=>({id:'ship'}),getDataSourceManager:()=>({}),getFlowRunner:()=>flow,getShowToast:()=>toast} as any;
    const block = {id:'actions',blockType:'form-buttons',buttons:[
      {code:'send',content:'Send',action:{type:'flow',steps:[
        {action:'state.ensureRequestKey',args:{field:'shipmentRequestKey'}},
        {action:'api.request',endpoint:'/api/example/ship',method:'post',body:{requestKey:'${state.shipmentRequestKey}',quantity:'${form.quantity}'}},
      ]}},
      {code:'refresh',content:'Refresh'},
      {code:'next',content:'Next attempt',action:{type:'flow',steps:[{action:'state.set',args:{shipmentRequestKey:null}}]}},
    ]} as any;
    render(<MemoryRouter><FormButtonsBlockRenderer block={block} runtime={runtime}/></MemoryRouter>);
    return {state,toast,loadData};
  }
  it('actual button→hook→flow→registry→POST retries reuse the key, while explicit next attempt renews', async () => {
    transport.mockReset().mockResolvedValueOnce({code:'400',message:'Rejected'}).mockResolvedValue({code:'0',data:{ok:true}});
    const {state,toast,loadData}=fixture();
    fireEvent.click(screen.getByTestId('form-btn-send'));
    await waitFor(()=>expect(toast).toHaveBeenCalled());
    const key=state.getContext('ship').state.shipmentRequestKey;
    expect(key).toMatch(/^[a-f0-9-]{36}$/);
    fireEvent.click(screen.getByTestId('form-btn-refresh'));
    await waitFor(()=>expect(loadData).toHaveBeenCalled());
    fireEvent.click(screen.getByTestId('form-btn-send'));
    await waitFor(()=>expect(transport).toHaveBeenCalledTimes(2));
    expect(transport.mock.calls.map(call=>call[1].params)).toEqual([{requestKey:key,quantity:'250.0000'},{requestKey:key,quantity:'250.0000'}]);
    fireEvent.click(screen.getByTestId('form-btn-next'));
    await waitFor(()=>expect(state.getContext('ship').state.shipmentRequestKey).toBeNull());
    fireEvent.click(screen.getByTestId('form-btn-send'));
    await waitFor(()=>expect(transport).toHaveBeenCalledTimes(3));
    expect(transport.mock.calls[2][1].params.requestKey).not.toBe(key);
  });
  it('invalid existing state fails before actual transport and leaves the intention untouched', async () => {
    transport.mockReset();const {state,toast}=fixture(false);
    fireEvent.click(screen.getByTestId('form-btn-send'));
    await waitFor(()=>expect(toast).toHaveBeenCalled());
    expect(transport).not.toHaveBeenCalled();
    expect(state.getContext('ship').state.shipmentRequestKey).toBe(false);
  });
});
