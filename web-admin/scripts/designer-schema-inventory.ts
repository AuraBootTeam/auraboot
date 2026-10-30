/** Inspect active native authoring registries; registration is never execution evidence. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import ts from 'typescript';
import {widgetDefinitions} from '../app/plugins/core-dashboard/widgets/widgetRegistry';
import {dashboardPropertySchemas} from '../app/plugins/core-dashboard/components/dashboardPropertySchemas';
import {reportAuthoringSchemas} from '../app/plugins/core-designer/components/report-designer/registry/reportAuthoringSchemas';
import {REPORT_BLOCK_TYPES} from '../app/plugins/core-designer/components/report-designer/registry/reportBlockRegistry';
import {createDefaultBlockRegistryV3} from '../app/plugins/core-designer/components/unified-designer/registry/BlockRegistry';
import {defaultInspectorSchemaRegistry as inspector} from '../app/plugins/core-designer/components/unified-designer/registry/InspectorSchemaRegistry';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const digest=(file:string)=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');
export function flattenFields(fields:any[],prefix=''):any[] {
  return fields.flatMap(field=>[{
    path:prefix+field.key,type:field.type,editor:field.editor||null,required:!!field.required,
    min:field.min??null,max:field.max??null,dependsOn:field.dependsOn||null,
    options:field.options?.map((option:any)=>option.value)||[],
    registeredAssertions:[],execution:'did_not_run',
  },...flattenFields(field.itemSchema||[],prefix+field.key+'[].')]);
}
export function collectDesignerSchemas() {
  const rows:any[]=[];
  const declarations=new Set<string>();
  for(const relative of ['shared/designer/PropertyFieldRenderer.tsx','shared/designer/ColorPropertyEditor.tsx','plugins/core-dashboard/components/dashboardPropertySchemas.tsx','plugins/core-designer/components/report-designer/components/ReportDataSourceEditor.tsx']) {
    const file=path.join(root,'web-admin/app',relative);
    const ast=ts.createSourceFile(file,fs.readFileSync(file,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
    const visit=(node:ts.Node)=>{
      if(ts.isCallExpression(node)&&node.expression.getText(ast)==='propertyEditorRegistry.register'&&node.arguments[0]&&ts.isStringLiteral(node.arguments[0]))declarations.add(node.arguments[0].text);
      ts.forEachChild(node,visit);
    };visit(ast);
  }
  const add=(surface:string,component:string,variant:string,fields:any[])=>{
    const properties=flattenFields(fields);
    for(const field of properties) if(field.editor&&!declarations.has(field.editor))throw Error('Undeclared registered editor: '+field.editor);
    rows.push({surface,component,variant,properties});
  };
  for(const widget of widgetDefinitions)add('Dashboard',widget.type,'native',dashboardPropertySchemas(widget.configSchema||[],key=>key,'zh-CN',widget.type));
  for(const type of REPORT_BLOCK_TYPES)add('Report',type.slice(7),'native',reportAuthoringSchemas(type.slice(7) as any,((value:any)=>value.zh) as any));
  for(const definition of createDefaultBlockRegistryV3().getAll()) {
    const block={id:'inventory',blockType:definition.blockType} as any;
    add('Page',definition.blockType,'default',inspector.getFieldsForBlock(block));
    const selector=inspector.getFields(definition.blockType).find(field=>field.key===(definition.blockType==='action'?'actionType':'props.component'));
    if(!['field','filter-field','action'].includes(definition.blockType))continue;
    for(const option of selector?.options||[]) {
      const value=String(option.value);
      const variant=definition.blockType==='action'?{...block,actionType:value}:{...block,props:{component:value}};
      add('Page',definition.blockType,value,inspector.getFieldsForBlock(variant));
    }
  }
  return rows;
}

const actionSources=[
  ['Page','plugins/core-designer/components/unified-designer/workbench/WorkbenchToolbar.tsx'],
  ['Page','plugins/core-designer/components/unified-designer/canvas/CanvasHost.tsx'],
  ['Dashboard','plugins/core-dashboard/components/DesignerToolbar.tsx'],
  ['Dashboard','plugins/core-dashboard/components/WidgetPalette.tsx'],
  ['Dashboard','plugins/core-dashboard/components/DesignerCanvas.tsx'],
  ['Report','plugins/core-designer/components/report-designer/components/ReportToolbar.tsx'],
  ['Report','plugins/core-designer/components/report-designer/components/BlockPalette.tsx'],
  ['Report','plugins/core-designer/components/report-designer/components/ReportCanvas.tsx'],
];
function collectActions() {
  return actionSources.flatMap(([surface,relative])=>{
    const file='web-admin/app/'+relative;const source=ts.createSourceFile(file,fs.readFileSync(path.join(root,file),'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
    const actions:any[]=[];
    function visit(node:ts.Node) {
      if(ts.isJsxAttribute(node)&&/^on[A-Z]/.test(node.name.getText(source)))actions.push({surface,file,line:source.getLineAndCharacterOfPosition(node.getStart()).line+1,event:node.name.getText(source),handler:node.initializer?.getText(source)||'',execution:'did_not_run'});
      ts.forEachChild(node,visit);
    }
    visit(source);return actions;
  });
}

export function writeInventory(output:string,runFile?:string) {
  const rows=collectDesignerSchemas();const commit=execFileSync('git',['-C',root,'rev-parse','HEAD'],{encoding:'utf8'}).trim();
  const clean=!execFileSync('git',['-C',root,'status','--porcelain'],{encoding:'utf8'}).trim();
  const bindingsFile=path.join(root,'web-admin/tests/helpers/designer-schema-bindings.json');
  const bindings=JSON.parse(fs.readFileSync(bindingsFile,'utf8'));
  const run=runFile?JSON.parse(fs.readFileSync(runFile,'utf8')):null;
  if(run && (!clean || run.sources?.core?.commit!==commit || path.resolve(run.sources.core.root)!==root || run.status!=='passed'))throw Error('Use a passed native run from the exact clean current Core source');
  for(const binding of bindings) {
    const driver=path.join(root,binding.driver);
    if(digest(driver)!==binding.driverSha256)throw Error('Stale assertion binding: '+binding.driver);
    const row=rows.find(row=>row.surface===binding.surface&&row.component===binding.component&&row.variant===binding.variant);
    if(!row)throw Error('Missing registered component for assertion binding');
    let executed=false;
    if(run && binding.profile) {
      const phase=run.phases.find((phase:any)=>phase.profile===binding.profile && phase.status==='passed');
      if(phase) {
        const native=JSON.parse(fs.readFileSync(path.join(path.dirname(runFile!),binding.profile,binding.profile+'-browser-results.json'),'utf8'));
        const specs:any[]=[];const walk=(suites:any[])=>suites.forEach(s=>{specs.push(...s.specs||[]);walk(s.suites||[]);});walk(native.suites||[]);
        executed=specs.some(spec=>spec.file?.endsWith(binding.driver.replace('web-admin/tests/e2e/',''))&&spec.title===binding.testTitle&&spec.tests?.length&&spec.tests.every((test:any)=>test.expectedStatus==='passed'&&test.status==='expected'&&test.results?.length===1&&test.results[0].status==='passed'));
      }
    }
    for(const key of binding.properties) {
      const field=row.properties.find((field:any)=>field.path===key);
      if(!field)throw Error('Assertion references an absent schema property: '+key);
      field.registeredAssertions.push({driver:binding.driver,testTitle:binding.testTitle,layer:binding.layer});
      if(executed)field.execution='passed';
    }
  }
  const sourceFiles=['plugins/core-dashboard/widgets/widgetRegistry.ts','plugins/core-dashboard/components/dashboardPropertySchemas.tsx','plugins/core-designer/components/report-designer/registry/reportAuthoringSchemas.ts','plugins/core-designer/components/unified-designer/registry/BlockRegistry.ts','plugins/core-designer/components/unified-designer/registry/InspectorSchemaRegistry.ts','shared/designer/PropertyFieldRenderer.tsx','shared/designer/ColorPropertyEditor.tsx','shared/designer/ArrayItemEditor.tsx'].map(file=>({file:'web-admin/app/'+file,sha256:digest(path.join(root,'web-admin/app',file))}));
  const report={format:'auraboot.designer-schema-inventory.v1',source:{root,commit,clean,files:sourceFiles},rows,actions:collectActions(),
    limitations:['Native registries only; runtime-only plugin contributions require their own registry/manifest audit','Source action callbacks are an inventory, not all executed DOM states','Registered assertions and passed browser evidence are separate; no unit result is inferred from registration'],
    bindingsSha256:digest(bindingsFile),nativeRun:runFile?path.resolve(runFile):null};
  fs.mkdirSync(output,{recursive:true});fs.writeFileSync(path.join(output,'inventory.json'),JSON.stringify(report,null,2)+'\n');
  const flat=rows.flatMap(row=>row.properties.map((field:any)=>({...field,surface:row.surface,component:row.component,variant:row.variant})));
  fs.writeFileSync(path.join(output,'inventory.md'),['# Native designer schema/action inventory','',`Core: ${commit}`,`Components and variants: ${rows.length}; properties: ${flat.length}; source callbacks: ${report.actions.length}`,'','Registration is not execution. See inventory.json for all untested properties and limitations.','','| Surface | Component | Variant | Property | Editor/type | Assertions | Execution |','| --- | --- | --- | --- | --- | --- | --- |',...flat.map(field=>`| ${field.surface} | ${field.component} | ${field.variant} | ${field.path} | ${field.editor||field.type} | ${field.registeredAssertions.length} | ${field.execution} |`)].join('\n')+'\n');
  return {components:rows.length,properties:flat.length,passed:flat.filter(field=>field.execution==='passed').length,registered:flat.filter(field=>field.registeredAssertions.length).length};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [output,runFile,...rest]=process.argv.slice(2);if(!output||rest.length)throw Error('Usage: tsx --tsconfig web-admin/tsconfig.json web-admin/scripts/designer-schema-inventory.ts OUTPUT [NATIVE_RUN_JSON]');
  console.log(JSON.stringify(writeInventory(path.resolve(output),runFile)));
}
