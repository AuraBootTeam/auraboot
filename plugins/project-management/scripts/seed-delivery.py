# -*- coding: utf-8 -*-
import importlib.util,json,urllib.request,os
from urllib.parse import urlparse
from pathlib import Path
from datetime import date,timedelta
core=Path(__file__).resolve().parents[3]
base=os.environ['DELIVERY_BACKEND_URL'].rstrip('/')
assert urlparse(base).hostname in ['localhost','127.0.0.1'],'Local owned runtime only'
s=importlib.util.spec_from_file_location('session',core/'scripts/application/core-admin-session.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def req(path,method='GET',body=None,headers=None):
 h=dict(headers or {});data=None
 if body is not None:h['Content-Type']='application/json';data=json.dumps(body).encode()
 with urllib.request.urlopen(urllib.request.Request(base+path,data=data,method=method,headers=h),timeout=120) as r:return json.load(r)
token=m.business_session(req,os.environ['ADMIN_EMAIL'],os.environ['ADMIN_PASSWORD']);h={'Authorization':'Bearer '+token};out=Path(os.environ['DELIVERY_EVIDENCE_ROOT']);out.mkdir(parents=True,exist_ok=True)
receipts=[]
def cmd(code,payload=None,pid=None):
 b={'payload':payload or {},'operationType':'update' if pid else 'create'}
 if pid:b['targetRecordPid']=pid
 v=req('/api/meta/commands/execute/'+code,'POST',b,h)
 assert str(v.get('code'))=='0' and v['data']['phaseReached']=='completed',v
 receipts.append(v);return v['data']
def pid(v):
 if isinstance(v.get('data'),dict):return pid(v['data'])
 for k in ['recordId','recordPid','targetRecordId','pid']:
  if v.get(k):return str(v[k])
 raise Exception('Missing record identity: '+str(v))
existing=req('/api/dynamic/tpm_project/list','GET',headers=h)['data']['records']
assert len(existing)==0,'Refuse to seed an already populated project fixture'
owners=[]
for login,name in [('delivery-lin','林晓'),('delivery-zhou','周宁'),('delivery-chen','陈晨'),('delivery-wang','王越')]:
 v=req('/api/admin/users','POST',{'userName':login,'displayName':name,'initialPassword':os.environ['ADMIN_PASSWORD'],'roleCodes':['tpm_delivery_manager'],'sendInviteEmail':False},h)
 assert str(v['code'])=='0',v
 owners.append(v['data']['userPid'])
viewer_email='delivery-viewer@example.test'
v=req('/api/admin/users','POST',{'email':viewer_email,'displayName':'交付观察员','initialPassword':os.environ['ADMIN_PASSWORD'],'roleCodes':['tpm_delivery_viewer'],'sendInviteEmail':False},h)
assert str(v['code'])=='0',v
owner=owners[0]
projects=[]
references=json.loads((Path(__file__).parent/'reference-projects.json').read_text())
states={'需求确认':'planning','实施中':'active','待验收':'acceptance','已交付':'completed'}
owner_names=['林晓','周宁','陈晨','王越']
for index,row in enumerate(references):
 name,customer,code,stage,_,owner_name,_,_,progress,date_text,_,budget=row
 status=states[stage]
 due='2026-'+date_text.replace('月','-').replace('日','')
 desc='整合供应商、采购与入库流程，为采购和仓储团队提供统一的协作工作台。'
 project=pid(cmd('tpm:create_project',{'tpm_pj_code':code,'tpm_pj_name':name,'tpm_pj_customer':customer,'tpm_pj_budget':int(budget.replace(',','')),'tpm_pj_owner_user':owners[owner_names.index(owner_name)],'tpm_pj_progress':progress,'tpm_pj_description':desc+'（虚构示例数据）','tpm_pj_start_date':'2026-09-08','tpm_pj_end_date':due,'tpm_pj_priority':'high' if index==0 else 'medium'}))
 if status!='planning':cmd('tpm:activate_project',pid=project)
 if status=='acceptance':cmd('tpm:submit_acceptance',pid=project)
 if status=='completed':cmd('tpm:complete_project',pid=project)
 ms=[]
 for j,(title,criteria,target) in enumerate([('需求范围确认','范围文档已由双方确认','2026-09-12'),('原型与方案评审','页面原型与技术方案已通过','2026-09-20'),('核心功能联调','采购与入库模块联调','2026-10-20'),('验收与上线','完成用户验收，准备上线',due)]):
  mid=pid(cmd('tpm:create_milestone',{'tpm_ms_name':title,'tpm_ms_project_id':project,'tpm_ms_target_date':target,'tpm_ms_description':criteria}))
  if j < (4 if status=='completed' else 2 if status in ['active','acceptance'] else 0):cmd('tpm:reach_milestone',pid=mid)
  ms.append(mid)
 projects.append({'name':name,'pid':project,'code':code,'progress':progress,'milestones':ms})
(out/'seed-receipts.json').write_text(json.dumps(receipts,ensure_ascii=False,indent=2));(out/'fixture.json').write_text(json.dumps({'fictional':True,'owner':owner,'owners':owners,'viewerEmail':viewer_email,'projects':projects},ensure_ascii=False,indent=2));print('Created the 8 approved reference projects and 32 real milestones through commands')
