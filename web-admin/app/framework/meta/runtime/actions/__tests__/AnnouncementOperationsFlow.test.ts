import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { actionRegistry } from '../ActionRegistry';
import { createRequestKeySession } from '../request-key/session';
import { ExpressionEvaluator } from '../../expression/evaluator';
import { ScopedStateManager } from '../../state/scoped-state';
import { FlowRunner } from '../../schema-runtime/FlowRunner';
import { canonicalizePageSchemaDto } from '../../../utils/canonicalizePageDsl';
import { validateStructure } from '../../../validation/DslValidator';

const pages=JSON.parse(readFileSync(resolve(import.meta.dirname,'../../../../../../../plugins/core-announcement/config/pages.json'),'utf8'));
const schema=pages.find((page:any)=>page.pageKey==='announcement_statistics_detail');
const flow=schema.blocks.find((block:any)=>block.id==='statistics_actions').buttons.find((button:any)=>button.code==='check_export_operation').action.steps;
function setup(result={found:true,exportTaskPid:'original-export',operationState:'accepted'}) {
  const manager=new ScopedStateManager({locale:'en-US',theme:'light'} as any);manager.createScope('statistics',{});
  const requestKeySession=createRequestKeySession(()=>({tenantId:'1',userId:'author',pageKey:schema.pageKey,recordPid:'capture'}));
  const fetchResult=vi.fn().mockResolvedValue({code:'0',data:result}),navigate=vi.fn(),showToast=vi.fn();
  const runner=new FlowRunner({evaluator:new ExpressionEvaluator(),actionRegistry,stateManager:manager,scopeId:'statistics',
    dataSourceManager:{} as any,schema,getAllFormFields:()=>[],navigate,showToast,requestKeySession});
  return {manager,requestKeySession,fetchResult,navigate,showToast,runner,
    context:()=>({...manager.getContext('statistics'),fetchResult,record:{snapshotToken:'capture'}}) as any};
}
afterEach(()=>sessionStorage.clear());
describe('Native announcement operational lookups',()=>{
  it('does not allocate an operation or call an API when there is no previous request',async()=>{
    const state=setup();await state.runner.run(flow,state.context());
    expect(state.fetchResult).not.toHaveBeenCalled();expect(state.navigate).not.toHaveBeenCalled();
    expect(state.showToast).toHaveBeenCalledWith('There is no pending export request to check.','info');
    expect(state.requestKeySession.peek('statisticsExportOperation',null)).toBeNull();
  });
  it('queries the original command and UUID then opens the returned existing export without clearing the intent',async()=>{
    const state=setup();const key=state.requestKeySession.ensure('statisticsExportOperation',null);
    await state.runner.run(flow,state.context());
    expect(state.fetchResult).toHaveBeenCalledExactlyOnceWith(`/api/announcements/operations/${key}`,
      {method:'get',params:{commandCode:'announcement:export_statistics'}});
    expect(state.navigate).toHaveBeenCalledExactlyOnceWith('/p/announcement_statistics_export/view/original-export');
    expect(state.requestKeySession.peek('statisticsExportOperation',null)).toBe(key);expect(state.showToast).not.toHaveBeenCalled();
  });
  it('keeps an unknown result uncertain and never creates or opens an export',async()=>{
    const state=setup({found:false,operationState:'unknown'} as any);const key=state.requestKeySession.ensure('statisticsExportOperation',null);
    await state.runner.run(flow,state.context());
    expect(state.fetchResult).toHaveBeenCalledOnce();expect(state.navigate).not.toHaveBeenCalled();
    expect(state.showToast).toHaveBeenCalledWith(expect.stringContaining('No committed result was found'),'info');
    expect(state.requestKeySession.peek('statisticsExportOperation',null)).toBe(key);
  });
  it('uses the native exact-version history detail and server paging schema',()=>{
    const history=pages.find((page:any)=>page.pageKey==='announcement_delivery_history_detail');
    expect(validateStructure(canonicalizePageSchemaDto(history))).toEqual([]);
    expect(history.extension.dataSource.detailEndpoint).toBe('/api/announcements/versions/{recordPid}/delivery-summary');
    expect(history.blocks.find((block:any)=>block.id==='delivery_rows').table.pagination.mode).toBe('server');
  });
});
