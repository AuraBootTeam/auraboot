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
 if pid:b['targetRecordId']=pid
 v=req('/api/meta/commands/execute/'+code,'POST',b,h)
 assert str(v.get('code'))=='0' and v['data']['phaseReached']=='completed',v
 receipts.append(v);return v['data']
def pid(v):
 if isinstance(v.get('data'),dict):return pid(v['data'])
 for k in ['recordId','recordPid','targetRecordId','pid']:
  if v.get(k):return str(v[k])
 raise Exception('Missing record identity: '+str(v))
users=req('/api/admin/users/search','GET',headers=h)
owners=[]
for login,name in [('delivery-lin','林悦'),('delivery-zhou','周予'),('delivery-chen','陈川')]:
 v=req('/api/admin/users','POST',{'userName':login,'displayName':name,'initialPassword':os.environ['ADMIN_PASSWORD'],'roleCodes':['tpm_delivery_manager'],'sendInviteEmail':False},h)
 assert str(v['code'])=='0',v
 owners.append(v['data']['userPid'])
owner=owners[0]
projects=[]
existing=req('/api/dynamic/tpm_project/list','GET',headers=h)['data']['records']
assert len(existing)==0,'Refuse to seed an already populated project fixture'
cases=[('青禾零售 · 会员系统上线','青禾零售',128000,3,'active','完成会员迁移、积分联调与门店培训，让会员权益在 12 家门店同步可用。'),('远山制造 · 售后工单门户','远山制造',86000,12,'active','统一设备报修、派工与服务回访，缩短跨部门协作时间。'),('星河物流 · 仓配数据看板','星河物流',68000,20,'planning','把订单履约、库存与配送异常汇总到业务看板。'),('知行教育 · 学员服务平台','知行教育',96000,7,'active','交付学员档案、课程安排和服务跟进流程。'),('澄川医疗 · 采购审批升级','澄川医疗',72000,16,'on_hold','梳理采购审批与预算核对，等待客户确认审批责任人。'),('南风餐饮 · 门店巡检应用','南风餐饮',48000,-2,'completed','统一巡检标准、整改跟踪和门店反馈。'),('松果科技 · 客户成功工作台','松果科技',110000,24,'planning','建立客户上线、使用反馈和续约提醒的团队协作流程。'),('拾光文旅 · 预约运营后台','拾光文旅',58000,10,'active','交付活动预约、名额管理与客户通知。')]
for index,(name,customer,budget,offset,status,desc) in enumerate(cases):
 project=pid(cmd('tpm:create_project',{'tpm_pj_name':name,'tpm_pj_customer':customer,'tpm_pj_budget':budget,'tpm_pj_owner_user':owners[index%len(owners)],'tpm_pj_description':desc+'（虚构示例数据）','tpm_pj_start_date':str(date.today()-timedelta(days=21)),'tpm_pj_end_date':str(date.today()+timedelta(days=offset)),'tpm_pj_priority':'high' if index==0 else 'medium'}))
 if status!='planning':cmd('tpm:activate_project',pid=project)
 if status=='on_hold':cmd('tpm:hold_project',pid=project)
 if status=='completed':cmd('tpm:complete_project',pid=project)
 ms=[]
 for j,(title,criteria) in enumerate([('需求与方案确认','确认业务范围、数据迁移口径与验收清单'),('核心功能交付','完成会员档案、权益配置和门店基础流程'),('积分联调验收','核对积分增减与异常回滚，完成跨系统联调'),('上线与团队培训','完成部署、操作培训与上线验收')]):
  mid=pid(cmd('tpm:create_milestone',{'tpm_ms_name':title if index==0 else ['范围与方案确认','核心流程交付','业务验收','上线与培训'][j],'tpm_ms_project_id':project,'tpm_ms_target_date':str(date.today()+timedelta(days=offset-9+j*3)),'tpm_ms_description':criteria}))
  if j < (4 if status=='completed' else 2 if status in ['active','on_hold'] else 0):cmd('tpm:reach_milestone',pid=mid)
  ms.append(mid)
 projects.append({'name':name,'pid':project,'milestones':ms})
(out/'seed-receipts.json').write_text(json.dumps(receipts,ensure_ascii=False,indent=2));(out/'fixture.json').write_text(json.dumps({'fictional':True,'owner':owner,'owners':owners,'projects':projects},ensure_ascii=False,indent=2));print('Created 8 fictional projects and 32 milestones through commands')
