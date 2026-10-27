import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root=new URL('../',import.meta.url);
const read=file=>JSON.parse(readFileSync(new URL(file,root),'utf8'));
test('every reference customer has distinct contact, outcome, risk and owned next action',()=>{
 const references=read('scripts/reference-projects.json');
 const fixture=read('scripts/delivery-project-context.json');
 assert.equal(fixture.fictional,true);
 assert.equal(references.length,8);
 assert.deepEqual(Object.keys(fixture.customers).sort(),references.map(row=>row[1]).sort());
 for(const row of references) {
  const customer=fixture.customers[row[1]];
  for(const key of ['contact','outcome','risk','nextAction']) assert.ok(customer[key]?.trim().length>5,`${row[1]} needs ${key}`);
  assert.ok(customer.nextAction.includes(row[5]),`${row[1]} next action needs its actual owner`);
 }
 assert.equal(new Set(Object.values(fixture.customers).map(row=>row.contact)).size,8);
 assert.equal(new Set(Object.values(fixture.customers).map(row=>row.outcome)).size,8);
});
test('risk and next action survive both normal project write commands and are editable in the DSL',()=>{
 const commands=read('config/commands/tpm_project.json');
 const bindings=read('config/bindings/tpm_project.json');
 const form=read('config/pages/tpm_project_form.json');
 for(const field of ['tpm_pj_risk_summary','tpm_pj_next_action']) {
  for(const code of ['tpm:create_project','tpm:update_project'])assert.ok(commands.find(cmd=>cmd.code===code).inputFields.includes(field));
  assert.equal(bindings.find(binding=>binding.fieldCode===field).editable,true);
  assert.ok(form.blocks.some(block=>block.id==='delivery_actions' && block.fields.some(item=>item.field===field)));
 }
});
test('portable delivery report reads governed sources and contains no embedded result rows',()=>{
 const report=read('examples/customer-delivery-brief.report-template.json');
 assert.equal(report.format,'auraboot.report-template');
 assert.equal(report.dsl.page.orientation,'landscape');
 assert.deepEqual(report.dsl.dataSources,{projects:{type:'namedQuery',queryCode:'tpm_delivery_brief'},customers:{type:'model',modelCode:'tpm_customer'}});
 const query=read('config/named-queries.json').find(row=>row.code==='tpm_delivery_brief');
 assert.equal(query.resourceCode,'tpm_project');assert.equal(query.actionCode,'read');
 assert.ok(query.parameter_schema.required.includes('tenantId'));
 assert.match(query.fromSql,/m\.tenant_id = p\.tenant_id/);
 assert.match(query.fromSql,/CASE WHEN m\.pid IS NOT NULL/);
 assert.doesNotMatch(query.fromSql,/CASE WHEN m\.id IS NOT NULL/);
 assert.match(query.fromSql,/p\.tenant_id = #\{params\.tenantId\}/);
 assert.equal(report.dsl.body.filter(block=>block.blockType==='table').length,3);
 for(const source of Object.values(report.dsl.dataSources))assert.equal(source.data,undefined);
 const columns=report.dsl.body.flatMap(block=>block.columns||[]).map(column=>column.field);
 for(const field of ['owner_name','delivery_stage','delivery_date','budget','risk_summary','next_action','tpm_cu_contact'])assert.ok(columns.includes(field));
});

test('project and acceptance attachment fields use the native file contract required by relation authorization',()=>{
 for(const [file,code] of [['tpm_project.json','tpm_pj_files'],['tpm_acceptance.json','tpm_ac_files']]) {
  const field=read('config/fields/'+file).find(row=>row.code===code);
  assert.ok(field);assert.equal(field.dataType,'file');
  assert.equal(field.extension.renderComponent,'fileattachment');
  assert.equal(field.extension.multiple,true);
 }
 for(const file of ['config/pages/tpm_project_form.json','config/pages/tpm_acceptance_form.json']) {
  const page=read(file);assert.equal(page.extension.linkFileAttachments,true);
  assert.equal(page.extension.attachmentFields.length,1);
 }
});
