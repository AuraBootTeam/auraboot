import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { FormButtonsBlockRenderer } from '../FormButtonsBlockRenderer';
import { ExpressionEvaluator } from '../../../runtime/expression/evaluator';
const owned=vi.hoisted(()=>({dispatch:vi.fn()}));
vi.mock('~/framework/meta/hooks/useActionHandler',()=>({useActionHandler:()=>({handleAction:owned.dispatch})}));
vi.mock('~/contexts/AuthContext',()=>({useAuth:()=>({token:'fixture',hasPermission:()=>true})}));
function fixture(button:any){
 const runtime={getContext:()=>({locale:'en',form:{blocked:false,ready:true}}),getEvaluator:()=>new ExpressionEvaluator(),getSchema:()=>({id:'example'}),getDataSourceManager:()=>({})} as any;
 return <MemoryRouter><FormButtonsBlockRenderer runtime={runtime} block={{id:'actions',blockType:'form-buttons',buttons:[button]} as any}/></MemoryRouter>;
}
describe('FormButtons malformed condition configuration',()=>{
 it('rejects malformed disableWhen before rendering an enabled action',()=>{
  expect(()=>render(fixture({code:'send',content:'Send',disableWhen:'${form.blocked > }',commandCode:'example:send'}))).toThrow('Invalid form button condition');
 });
 it('preserves legal enableWhen precedence and dispatch behavior',()=>{
  owned.dispatch.mockClear();render(fixture({code:'send',content:'Send',enableWhen:'${form.ready}',disableWhen:'${!form.blocked}',commandCode:'example:send'}));
  expect(screen.getByTestId('form-btn-send')).not.toBeDisabled();fireEvent.click(screen.getByTestId('form-btn-send'));expect(owned.dispatch).toHaveBeenCalledTimes(1);
 });
 it('rejects configuration drift at the actual click boundary instead of dispatching malformed disableWhen',()=>{
  owned.dispatch.mockClear();const button={code:'send',content:'Send',disableWhen:'${form.blocked}',commandCode:'example:send'};
  render(fixture(button));const failures:string[]=[];const listener=(event:ErrorEvent)=>{failures.push(event.error?.message);event.preventDefault();};window.addEventListener('error',listener);
  try{button.disableWhen='${form.blocked > }';fireEvent.click(screen.getByTestId('form-btn-send'));expect(owned.dispatch).not.toHaveBeenCalled();expect(failures).toContain('Invalid form button condition');
   button.disableWhen='${form.blocked}';fireEvent.click(screen.getByTestId('form-btn-send'));expect(owned.dispatch).toHaveBeenCalledTimes(1);
  }finally{window.removeEventListener('error',listener);}
 });
});
