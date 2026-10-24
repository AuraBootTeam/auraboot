import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';
import { ActionCancelledError } from '~/framework/meta/runtime/actions/ActionCancelledError';
import { ExpressionEvaluator } from '~/framework/meta/runtime/expression/evaluator';
import { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';
import { FlowRunner } from '~/framework/meta/runtime/schema-runtime/FlowRunner';
import { canonicalizePageSchemaDto } from '~/framework/meta/utils/canonicalizePageDsl';
import { validateStructure } from '~/framework/meta/validation/DslValidator';
import { buildDetailRecordEndpoint, resolveDetailRecordEndpoint } from '~/framework/meta/rendering/pages/detailPage/detailRecordRequests';

const pages=JSON.parse(readFileSync(resolve(import.meta.dirname,'../../../../../../../plugins/core-announcement/config/pages.json'),'utf8'));
const schema=pages.find((page:any)=>page.pageKey==='announcement_policy_detail');
const button=schema.blocks.find((block:any)=>block.id==='policy_actions').buttons[0];
const input={forceApproval:true,defaultDefinitionPid:'old-flow',clearDefaultDefinition:true,lateWindowMinutes:30,
  reminderAt:'10:30',automaticReminderLimit:3,reminderDedupeMinutes:60,quietStart:'',quietEnd:''};
function setup() {
  const manager=new ScopedStateManager({locale:'en-US',theme:'light'} as any);manager.createScope('policy',{});
  const fetchResult=vi.fn().mockImplementation(async (_url:string,request:any)=>({code:'0',data:request.method==='get' ? [] : {operationState:'completed'}}));
  const loadData=vi.fn(),showToast=vi.fn();
  const runner=new FlowRunner({evaluator:new ExpressionEvaluator(),actionRegistry,stateManager:manager,scopeId:'policy',dataSourceManager:{reload:vi.fn()} as any,
    schema,getAllFormFields:()=>[],loadData,showToast});
  const context=()=>({...manager.getContext('policy'),fetchResult,record:{revision:0,forceApproval:false,
    defaultDefinitionPid:null,lateWindowMinutes:30,reminderAt:'09:00',automaticReminderLimit:3,
    reminderDedupeMinutes:60,quietStart:null,quietEnd:null}}) as any;
  return {manager,fetchResult,loadData,showToast,runner,context};
}
function submit(){window.addEventListener('dialog:form',event=>(event as CustomEvent).detail.onSubmit(input),{once:true});}
const writes=(state:ReturnType<typeof setup>)=>state.fetchResult.mock.calls.filter(([,request])=>request.method==='post');

describe('Native tenant announcement policy',()=>{
  it('loads the tenant singleton through the actual detail request builder without appending a record id',()=>{
    const canonical=canonicalizePageSchemaDto(schema);
    expect(buildDetailRecordEndpoint('ab_announcement','current',canonical)).toBe('/api/announcements/policy');
    expect(resolveDetailRecordEndpoint(canonical,'ab_announcement','current')).toEqual({endpoint:'/api/announcements/policy',method:'get'});
  });
  it('uses valid native DSL without displaying internal workflow identities',()=>{
    expect(validateStructure(canonicalizePageSchemaDto(schema))).toEqual([]);
    const fields=schema.blocks.find((block:any)=>block.id==='policy_current').fields;
    expect(fields.map((field:any)=>field.field)).not.toContain('defaultDefinitionPid');
    expect(button.permissionCode).toBe('announcement.announcement.policy_manage');
  });
  it('binds the tenant policy CAS and explicit clears through the real flow interpreter',async()=>{
    const state=setup();submit();await state.runner.run(button.action.steps,state.context());
    expect(writes(state)).toHaveLength(1);const [url,request]=writes(state)[0];
    expect(url).toBe('/api/meta/commands/execute/announcement:update_policy');
    expect(request.params.clientRequestId).toMatch(/^[0-9a-f-]{36}$/);
    expect(request.params).not.toHaveProperty('targetRecordPid');
    expect(request.params.payload).toEqual({expectedPolicyRevision:0,forceApproval:true,defaultDefinitionPid:'',
      lateWindowMinutes:30,reminderAt:'10:30',automaticReminderLimit:3,reminderDedupeMinutes:60,quietStart:'',quietEnd:''});
    expect(state.loadData).toHaveBeenCalledOnce();
    expect(state.manager.getContext('policy').state.announcementPolicyOperation).toBeNull();
    expect(state.showToast).toHaveBeenCalledWith('Announcement policy saved. Existing announcements keep their frozen reminder schedule.','success');
  });
  it('retains a failed operation for explicit replay and shows success only after acceptance',async()=>{
    const state=setup();let posts=0;
    state.fetchResult.mockImplementation(async (_url,request)=>request.method==='get' ? {code:'0',data:[]} :
      (++posts===1 ? {code:'CONFLICT',data:null} : {code:'0',data:{operationState:'completed'}}));
    submit();await expect(state.runner.run(button.action.steps,state.context())).rejects.toThrow();
    const original=state.manager.getContext('policy').state.announcementPolicyOperation;
    expect(original).toMatch(/^[0-9a-f-]{36}$/);expect(state.loadData).not.toHaveBeenCalled();expect(state.showToast).not.toHaveBeenCalled();
    submit();await state.runner.run(button.action.steps,state.context());
    expect(writes(state).map(([,request])=>request.params.clientRequestId)).toEqual([original,original]);
  });
  it('cancelling leaves policy and the operation ledger unchanged',async()=>{
    const state=setup();window.addEventListener('dialog:form',event=>(event as CustomEvent).detail.onCancel(),{once:true});
    await expect(state.runner.run(button.action.steps,state.context())).rejects.toBeInstanceOf(ActionCancelledError);
    expect(writes(state)).toHaveLength(0);expect(state.loadData).not.toHaveBeenCalled();expect(state.showToast).not.toHaveBeenCalled();
    expect(state.manager.getContext('policy').state.announcementPolicyOperation).toBeUndefined();
  });
});
