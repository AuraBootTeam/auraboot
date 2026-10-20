import React, { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RuntimeFieldRenderer } from '../RuntimeFieldRenderer';
import { Select } from '~/ui/smart/form/Select';
import { ScopedStateManager } from '../../runtime/state/scoped-state';
import { convertSchemaToUnified } from '~/plugins/core-designer/components/studio/services/runtime/SchemaRuntimeAdapter';
vi.mock('react-router', async () => ({...await vi.importActual<any>('react-router'),useActionData:()=>null}));
vi.mock('~/contexts/AuthContext', () => ({usePermission:()=>true}));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({useActionHandler:()=>({executeCommand:vi.fn()})}));
vi.mock('~/framework/meta/hooks/useFieldDataSource', () => ({useFieldDataSource:({staticOptions}:any)=>({options:staticOptions,loading:false,error:null,refetch:vi.fn()})}));
vi.mock('../components/ComponentLoader', () => ({ComponentLoader:({componentName,props}:any)=>{
  if(componentName!=='SmartSelect')throw Error('Wrong actual component');
  return <Select {...props}/>;
}}));

describe('Declared boolean Select through schema adapter and actual runtime field renderer', () => {
  it('preserves literal boolean props through conversion, typed false into form, and explicit unset', () => {
    const schema=convertSchemaToUnified({id:'typed',components:[{id:'block',type:'form-section',components:[{id:'tax',type:'SmartSelect',props:{name:'taxIncluded',label:'Tax',valueType:'boolean',options:[{value:true,label:'Yes'},{value:false,label:'No'}],clearable:true,placeholder:'Unset'}}]}]} as any);
    const field=schema.blocks![0].fields![0];
    expect(field.props?.valueType).toBe('boolean');
    expect(field.props?.options[1].value).toBe(false);
    const state=new ScopedStateManager({locale:'en',theme:'light'} as any);state.createScope('typed');
    const runtime={getContext:()=>({...state.getContext('typed'),t:(key:string)=>key}),getStateManager:()=>state,getScopeId:()=> 'typed',getDataSourceManager:()=>({notifyStateChanged:vi.fn()}),triggerFieldLinkage:vi.fn()} as any;
    function Harness(){const [value,setValue]=useState<unknown>();return <RuntimeFieldRenderer field={field} runtime={runtime} value={value} onChange={setValue}/>;}
    render(<Harness/>);
    expect(screen.getByRole('combobox',{name:'Tax'})).toHaveTextContent('Unset');
    expect(state.getFieldValue('typed','taxIncluded')).toBeUndefined();
    fireEvent.click(screen.getByRole('combobox',{name:'Tax'}));
    fireEvent.click(screen.getByRole('option',{name:'No'}));
    expect(state.getFieldValue('typed','taxIncluded')).toBe(false);
    expect(screen.getByRole('combobox',{name:'Tax'})).toHaveTextContent('No');
    fireEvent.click(screen.getByRole('button',{name:/Tax/}));
    expect(state.getFieldValue('typed','taxIncluded')).toBeNull();
    expect(screen.getByRole('combobox',{name:'Tax'})).toHaveTextContent('Unset');
  });
});
