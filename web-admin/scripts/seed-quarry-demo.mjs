#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { request } from '@playwright/test';

const BASE_URL = process.env.DEMO_BASE_URL || 'http://localhost:5173';
const STORAGE_STATE = process.env.DEMO_STORAGE_STATE || 'tests/storage/admin.json';
const OUTPUT_FILE = process.env.DEMO_OUTPUT_FILE || 'tests/storage/quarry-demo-seed-result.json';

function uid(prefix = 'DEMO') {
  const ts = Date.now();
  const rand = Math.random().toString(36).slice(2, 7).toUpperCase();
  return `${prefix}_${ts}_${rand}`;
}

function dateOffset(days) {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}

function pageKey(modelCode) {
  return modelCode.replace(/_/g, '-');
}

function ensureFileExists(filePath) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`Missing storage state: ${filePath}. Run ./scripts/reset-and-init.sh first, then retry.`);
  }
}

async function main() {
  ensureFileExists(path.resolve(process.cwd(), STORAGE_STATE));

  const api = await request.newContext({
    baseURL: BASE_URL,
    storageState: STORAGE_STATE,
    extraHTTPHeaders: {
      'Content-Type': 'application/json',
    },
  });

  const batchTag = `QUARRY_DEMO_${new Date().toISOString().slice(0, 10)}_${uid('BATCH')}`;
  const summary = {
    baseUrl: BASE_URL,
    batchTag,
    generatedAt: new Date().toISOString(),
    projects: [],
    contracts: [],
    contractChanges: [],
    dailyReports: [],
    weeklyReports: [],
    materialInspections: [],
    siteIssues: [],
    equipmentInspections: [],
    hazards: [],
    issues: [],
    inspectionTasks: [],
    qualityStandards: [],
    qualityCheckpoints: [],
    annualPlans: [],
    docCategories: [],
    documents: [],
    docVersions: [],
    articles: [],
    projectDocLinks: [],
  };

  async function execCommand(commandCode, payload = {}, targetRecordId, operationType, options = {}) {
    const data = { payload };
    if (targetRecordId) data.targetRecordId = targetRecordId;
    if (operationType) data.operationType = operationType;

    const resp = await api.post(`/api/meta/commands/execute/${commandCode}`, { data });
    const body = await resp.json().catch(() => ({}));
    const bodyData = body?.data ?? {};
    const nestedData = bodyData?.data ?? {};

    const code = String(
      body?.code ??
      bodyData?.code ??
      nestedData?.code ??
      (bodyData?.success === true ? '0' : '')
    );

    const recordId = String(
      nestedData?.recordId ??
      bodyData?.recordId ??
      body?.recordId ??
      nestedData?.pid ??
      bodyData?.pid ??
      body?.pid ??
      nestedData?.id ??
      bodyData?.id ??
      body?.id ??
      ''
    );

    const ok = resp.ok() && (code === '0' || code === '200');
    if (!ok && !options.allowFailure) {
      throw new Error(
        `Command ${commandCode} failed: HTTP ${resp.status()} code=${code} body=${JSON.stringify(body).slice(0, 800)}`,
      );
    }

    return { ok, code, recordId, body };
  }

  async function listByFilter(modelCode, filters, pageSize = 100) {
    const resp = await api.get(
      `/api/dynamic/${pageKey(modelCode)}/list?pageSize=${pageSize}&filters=${encodeURIComponent(JSON.stringify(filters))}`,
    );
    if (!resp.ok()) {
      throw new Error(`List query failed for ${modelCode}: HTTP ${resp.status()}`);
    }
    const body = await resp.json();
    return body?.data?.records ?? body?.data?.list ?? [];
  }

  async function getRecord(modelCode, recordId) {
    const resp = await api.get(`/api/dynamic/${pageKey(modelCode)}/${recordId}`);
    if (!resp.ok()) {
      throw new Error(`Get record failed for ${modelCode}/${recordId}: HTTP ${resp.status()}`);
    }
    const body = await resp.json();
    return body?.data ?? body;
  }

  async function transitionAnnualPlan(commandCode, planId, fallbackRemark) {
    const direct = await execCommand(commandCode, {}, planId, 'STATE_TRANSITION', { allowFailure: true });
    if (direct.ok) return direct;

    const plan = await getRecord('ap_annual_plan', planId);
    return execCommand(
      commandCode,
      {
        ap_project_id: plan.ap_project_id,
        ap_stat_year: plan.ap_stat_year,
        ap_plan_name: plan.ap_plan_name,
        ap_plan_remark: fallbackRemark ?? (plan.ap_plan_remark || ''),
      },
      planId,
      'STATE_TRANSITION',
    );
  }

  console.log(`[seed] batchTag: ${batchTag}`);

  // 1) Project master data
  const clients = [
    '华东基础设施集团',
    '锦海产业发展有限公司',
    '岭南城市建设集团',
  ];
  for (const name of clients) {
    await execCommand('pm:create_client', {
      pm_cl_name: `${name} ${batchTag}`,
      pm_cl_contact_person: '刘伟',
      pm_cl_phone: '13800001234',
      pm_cl_email: 'biz.demo@example.com',
      pm_cl_address: '上海市浦东新区',
    });
  }

  const subcontractors = [
    { name: '晟达钻采施工队', category: 'DRILLING' },
    { name: '安信安全技术服务', category: 'SAFETY' },
  ];
  for (const s of subcontractors) {
    await execCommand('pm:create_subcontractor', {
      pm_sc_name: `${s.name} ${batchTag}`,
      pm_sc_category: s.category,
      pm_sc_contact_person: '陈浩',
      pm_sc_phone: '13900004567',
      pm_sc_email: 'sc.demo@example.com',
      pm_sc_qualification_date: dateOffset(-30),
    });
  }

  // 2) Projects
  const projectDefs = [
    {
      key: 'A',
      name: '西岭矿区数字化升级',
      start: dateOffset(-45),
      end: dateOffset(180),
      budget: 12500000,
      story: '标杆项目，进度稳定、回款健康。',
    },
    {
      key: 'B',
      name: '南山爆破工艺优化',
      start: dateOffset(-30),
      end: dateOffset(150),
      budget: 8900000,
      story: '风险项目，设备问题导致成本压力上升。',
    },
    {
      key: 'C',
      name: '北湖绿色骨料试点',
      start: dateOffset(-15),
      end: dateOffset(220),
      budget: 6400000,
      story: '新启动项目，仍处于规划和早期上报阶段。',
    },
  ];

  const projects = {};
  for (const def of projectDefs) {
    const created = await execCommand('pm:create_project', {
      pm_project_name: `${def.name} ${batchTag}`,
      pm_project_code: `${def.key}-${uid('PRJ').slice(-8)}`,
      pm_project_type: 'MINE',
      pm_project_manager: '王军',
      pm_start_date: def.start,
      pm_end_date: def.end,
      pm_total_budget: def.budget,
      pm_location: '东区矿场',
      pm_description: def.story,
    });
    projects[def.key] = created.recordId;
    summary.projects.push({ key: def.key, id: created.recordId, name: `${def.name} ${batchTag}` });
  }

  // 3) WBS + milestone
  const wbsPhase = await execCommand('pm:create_wbs_node', {
    pm_wbs_project_id: projects.A,
    pm_wbs_code: `WBS-${uid('P').slice(-6)}`,
    pm_wbs_name: `阶段一-场地准备 ${batchTag}`,
    pm_wbs_node_type: 'PHASE',
    pm_wbs_assignee: '李强',
    pm_wbs_planned_start: dateOffset(-20),
    pm_wbs_planned_end: dateOffset(20),
    pm_wbs_estimated_hours: 160,
  });

  await execCommand('pm:create_wbs_node', {
    pm_wbs_project_id: projects.A,
    pm_wbs_parent_id: wbsPhase.recordId,
    pm_wbs_code: `WBS-${uid('T').slice(-6)}`,
    pm_wbs_name: `任务一-破碎站基础施工 ${batchTag}`,
    pm_wbs_node_type: 'TASK',
    pm_wbs_assignee: '赵明',
    pm_wbs_planned_start: dateOffset(-10),
    pm_wbs_planned_end: dateOffset(10),
    pm_wbs_estimated_hours: 72,
  });

  await execCommand('pm:create_milestone', {
    pm_ms_project_id: projects.A,
    pm_ms_name: `主设备到场节点 ${batchTag}`,
    pm_ms_due_date: dateOffset(7),
    pm_ms_responsible: '王军',
    pm_ms_description: '破碎机与输送线设备到场验收节点',
  });

  // 4) Contract + cost + payment + change
  async function createContractChain(projectId, projectLabel, amount, contractType, mode = 'running') {
    const created = await execCommand('cc:create_contract', {
      cc_contract_name: `${projectLabel} 主合同 ${batchTag}`,
      cc_contract_project_id: projectId,
      cc_contract_type: contractType,
      cc_party_a: '业主单位',
      cc_party_b: '总承包单位',
      cc_contract_amount: amount,
      cc_signed_date: dateOffset(-20),
      cc_start_date: dateOffset(-15),
      cc_end_date: dateOffset(180),
      cc_description: `${projectLabel} 演示合同数据`,
    });

    const contractId = created.recordId;
    summary.contracts.push({ project: projectLabel, id: contractId, amount, contractType });

    await execCommand('cc:submit_review', {}, contractId, 'STATE_TRANSITION');
    await execCommand('cc:approve_contract', {}, contractId, 'STATE_TRANSITION');

    if (mode === 'running' || mode === 'closed') {
      await execCommand('cc:start_execution', {}, contractId, 'STATE_TRANSITION');
    }
    if (mode === 'closed') {
      await execCommand('cc:settle_contract', {}, contractId, 'STATE_TRANSITION');
      await execCommand('cc:close_contract', {}, contractId, 'STATE_TRANSITION');
    }

    const budget = await execCommand('cc:create_budget', {
      cc_budget_project_id: projectId,
      cc_budget_name: `${projectLabel} 预算 ${batchTag}`,
      cc_budget_total_amount: Math.round(amount * 0.75),
    });

    await execCommand('cc:create_budget_line', {
      cc_bl_budget_id: budget.recordId,
      cc_bl_category: 'LABOR',
      cc_bl_description: `${projectLabel} 人工成本`,
      cc_bl_quantity: 100,
      cc_bl_unit_price: 1200,
      cc_bl_amount: 120000,
    });

    await execCommand('cc:create_budget_line', {
      cc_bl_budget_id: budget.recordId,
      cc_bl_category: 'PROCUREMENT',
      cc_bl_description: `${projectLabel} 设备材料成本`,
      cc_bl_quantity: 1,
      cc_bl_unit_price: 300000,
      cc_bl_amount: 300000,
    });

    await execCommand('cc:create_actual_cost', {
      cc_ac_project_id: projectId,
      cc_ac_budget_id: budget.recordId,
      cc_ac_category: 'LABOR',
      cc_ac_amount: 108000,
      cc_ac_date: dateOffset(-3),
      cc_ac_description: `${projectLabel} 人工结算`,
    });

    await execCommand('cc:create_actual_cost', {
      cc_ac_project_id: projectId,
      cc_ac_budget_id: budget.recordId,
      cc_ac_category: 'PROCUREMENT',
      cc_ac_amount: 285000,
      cc_ac_date: dateOffset(-2),
      cc_ac_description: `${projectLabel} 备件采购`,
    });

    await execCommand('cc:create_payment', {
      cc_pr_contract_id: contractId,
      cc_pr_type: 'RECEIPT',
      cc_pr_amount: Math.round(amount * 0.35),
      cc_pr_date: dateOffset(-1),
      cc_pr_remark: `${projectLabel} 一期回款`,
    });

    if (projectLabel.includes('B')) {
      await execCommand('cc:create_payment', {
        cc_pr_contract_id: contractId,
        cc_pr_type: 'PAYMENT',
        cc_pr_amount: 180000,
        cc_pr_date: dateOffset(0),
        cc_pr_remark: `${projectLabel} 应急维修付款`,
      });

      const change = await execCommand('cc:create_change', {
        cc_change_contract_id: contractId,
        cc_change_type: 'AMOUNT',
        cc_change_amount: 240000,
        cc_change_reason: `新增安全防护改造范围 ${batchTag}`,
      });
      await execCommand('cc:submit_change', {}, change.recordId, 'STATE_TRANSITION');
      await execCommand('cc:approve_change', {}, change.recordId, 'STATE_TRANSITION');
      summary.contractChanges.push({ id: change.recordId, contractId, status: 'APPROVED' });
    }

    return contractId;
  }

  await createContractChain(projects.A, '项目A', 5600000, 'CONSTRUCTION', 'running');
  await createContractChain(projects.B, '项目B', 4200000, 'PROCUREMENT', 'running');
  await createContractChain(projects.C, '项目C', 2600000, 'CONSULTING', 'closed');

  // 5) Quarry operation daily reports
  const reportPlan = [
    { project: projects.A, key: 'A', baseOffset: -6 },
    { project: projects.B, key: 'B', baseOffset: -5 },
    { project: projects.C, key: 'C', baseOffset: -4 },
  ];

  for (const r of reportPlan) {
    for (let i = 0; i < 2; i++) {
      const created = await execCommand('qo:create_daily_report', {
        qo_project_id: r.project,
        qo_report_date: dateOffset(r.baseOffset + i),
        qo_explosive_usage: 65 + i * 5,
        qo_detonator_usage: 62 + i * 6,
        qo_remark: `${r.key}号项目生产运营上报 ${batchTag} 第${i + 1}`,
      });

      await execCommand('qo:add_report_line', {
        qo_report_id: created.recordId,
        qo_product_category: 'STONE',
        qo_product_spec: i === 0 ? '10-20mm' : '20-31.5mm',
        qo_output: 320 + i * 40,
        qo_sales_qty: 280 + i * 35,
        qo_sales_amount: 168000 + i * 26000,
        qo_base_price: 580,
      });

      if (!(r.key === 'C' && i === 1)) {
        await execCommand('qo:submit_daily_report', {}, created.recordId, 'STATE_TRANSITION');
      }

      summary.dailyReports.push({ project: r.key, id: created.recordId, date: dateOffset(r.baseOffset + i) });
    }
  }

  // 6) Construction process: logs + weekly report + material inspection + site issue + equipment inspection
  await execCommand('cp:create_log', {
    cp_log_project_id: projects.A,
    cp_log_date: dateOffset(-2),
    cp_log_weather: 'CLOUDY',
    cp_log_temperature: '18~24',
    cp_log_workers_count: 46,
    cp_log_content: `破碎站安装施工中 ${batchTag}`,
    cp_log_issues: '输送线对中存在轻微延迟',
    cp_log_safety_notes: '现场人员已全员佩戴劳保及气体检测设备',
  });

  const weeklyApproved = await execCommand('cp:create_report', {
    cp_wr_project_id: projects.A,
    cp_wr_week_start: dateOffset(-7),
    cp_wr_week_end: dateOffset(-1),
    cp_wr_summary: `周进展汇总（审批通过） ${batchTag}`,
    cp_wr_progress: 62.5,
    cp_wr_next_plan: '完成联调联试与冷态试运行',
    cp_wr_issues: '振动传感器到货等待中',
  });
  await execCommand('cp:submit_report', {}, weeklyApproved.recordId, 'STATE_TRANSITION');
  await execCommand('cp:approve_report', {}, weeklyApproved.recordId, 'STATE_TRANSITION');
  summary.weeklyReports.push({ id: weeklyApproved.recordId, status: 'APPROVED' });

  const weeklyRejected = await execCommand('cp:create_report', {
    cp_wr_project_id: projects.B,
    cp_wr_week_start: dateOffset(-7),
    cp_wr_week_end: dateOffset(-1),
    cp_wr_summary: `周进展汇总（驳回样例） ${batchTag}`,
    cp_wr_progress: 49.0,
    cp_wr_next_plan: '调整爆破作业时间计划',
    cp_wr_issues: '检查取证材料不足',
  });
  await execCommand('cp:submit_report', {}, weeklyRejected.recordId, 'STATE_TRANSITION');
  await execCommand('cp:reject_report', {}, weeklyRejected.recordId, 'STATE_TRANSITION');
  summary.weeklyReports.push({ id: weeklyRejected.recordId, status: 'REJECTED' });

  const materialPassed = await execCommand('cp:create_inspection', {
    cp_mi_project_id: projects.A,
    cp_mi_material_name: `水泥A批次 ${batchTag}`,
    cp_mi_specification: 'P.O 42.5R',
    cp_mi_quantity: 120,
    cp_mi_unit: 'ton',
    cp_mi_supplier: '华新建材',
    cp_mi_inspection_date: dateOffset(-1),
    cp_mi_inspector: '陈科',
    cp_mi_remark: '主体混凝土浇筑材料',
  });
  await execCommand('cp:start_inspection', {}, materialPassed.recordId, 'STATE_TRANSITION');
  await execCommand('cp:pass_inspection', { cp_mi_remark: '抗压强度抽检合格' }, materialPassed.recordId, 'STATE_TRANSITION');
  summary.materialInspections.push({ id: materialPassed.recordId, result: 'PASSED' });

  const materialFailed = await execCommand('cp:create_inspection', {
    cp_mi_project_id: projects.B,
    cp_mi_material_name: `密封剂B批次 ${batchTag}`,
    cp_mi_specification: 'AS-220',
    cp_mi_quantity: 400,
    cp_mi_unit: 'bag',
    cp_mi_supplier: '南北化工',
    cp_mi_inspection_date: dateOffset(-1),
    cp_mi_inspector: '唐磊',
    cp_mi_remark: '检维修用密封材料',
  });
  await execCommand('cp:start_inspection', {}, materialFailed.recordId, 'STATE_TRANSITION');
  await execCommand('cp:fail_inspection', { cp_mi_remark: '黏度超出验收范围' }, materialFailed.recordId, 'STATE_TRANSITION');
  summary.materialInspections.push({ id: materialFailed.recordId, result: 'FAILED' });

  const siteIssue = await execCommand('cp:create_issue', {
    cp_si_project_id: projects.B,
    cp_si_title: `电缆沟进水问题 ${batchTag}`,
    cp_si_description: '临时电缆沟区域发现渗水',
    cp_si_category: 'QUALITY',
    cp_si_severity: 'HIGH',
    cp_si_assignee: '何强',
    cp_si_due_date: dateOffset(2),
  });
  await execCommand('cp:start_issue', {}, siteIssue.recordId, 'STATE_TRANSITION');
  await execCommand('cp:create_follow_up', {
    cp_fu_issue_id: siteIssue.recordId,
    cp_fu_date: dateOffset(0),
    cp_fu_action: '已加装临时排水与防护盖板',
    cp_fu_result: '风险明显下降',
    cp_fu_next_step: '48小时内完成永久排水方案优化',
    cp_fu_handler: '何强',
  });
  await execCommand('cp:resolve_issue', { cp_si_resolution: '已修正排水坡度并抬高电缆桥架' }, siteIssue.recordId, 'STATE_TRANSITION');
  await execCommand('cp:close_issue', {}, siteIssue.recordId, 'STATE_TRANSITION');
  summary.siteIssues.push({ id: siteIssue.recordId, status: 'CLOSED', category: 'QUALITY', severity: 'HIGH' });

  const equipment = await execCommand('cp:create_equipment_inspection', {
    cp_ei_project_id: projects.B,
    cp_ei_equipment_name: `2号主破碎机 ${batchTag}`,
    cp_ei_equipment_type: 'CRANE',
    cp_ei_model_spec: 'PCX-900',
    cp_ei_manufacturer: '江苏重工',
    cp_ei_inspection_date: dateOffset(-1),
    cp_ei_inspector: '孙磊',
    cp_ei_next_inspection_date: dateOffset(30),
    cp_ei_remark: '轴承温度一度超过预警阈值',
  });
  await execCommand('cp:start_equipment_inspection', {}, equipment.recordId, 'STATE_TRANSITION');
  await execCommand('cp:pass_equipment_inspection', {}, equipment.recordId, 'STATE_TRANSITION');
  summary.equipmentInspections.push({ id: equipment.recordId, result: 'PASSED', equipmentType: 'CRANE' });

  // 7) Safety & quality: hazard + issue branches + inspection task + checkpoints
  const hazard1 = await execCommand('dp:create_hazard_source', {
    dp_hs_name: `炸药库温度风险 ${batchTag}`,
    dp_hs_category: 'EQUIPMENT_STATE',
    dp_hs_level: 'MAJOR',
    dp_hs_area: '库区',
    dp_hs_description: '炸药库下午时段温度偏高',
  });
  const hazard2 = await execCommand('dp:create_hazard_source', {
    dp_hs_name: `夜班司机疲劳风险 ${batchTag}`,
    dp_hs_category: 'HUMAN_BEHAVIOR',
    dp_hs_level: 'GENERAL',
    dp_hs_area: '采区',
    dp_hs_description: '夜班前休息不足导致疲劳驾驶风险',
  });
  summary.hazards.push({ id: hazard1.recordId }, { id: hazard2.recordId });

  const issueRectify = await execCommand('dp:create_issue', {
    dp_issue_title: `输送机防护罩缺失 ${batchTag}`,
    dp_issue_content: '现场检查发现转运点防护罩缺失',
    dp_issue_area: '一段破碎线',
    dp_issue_source: 'DAILY_INSPECTION',
    dp_issue_project_id: projects.B,
    dp_issue_hazard_source_id: hazard1.recordId,
  });
  await execCommand('dp:submit_issue', {}, issueRectify.recordId, 'STATE_TRANSITION');
  await execCommand('dp:triage_issue', {
    dp_triage_decision: 'NEED_RECTIFY',
    dp_hazard_level: 'HIGH',
    dp_hazard_factor: 'MACHINE',
    dp_rectify_area: '破碎作业区',
    dp_rectify_dept: '运行一班',
    dp_triage_remark: '夜班前必须完成整改',
  }, issueRectify.recordId, 'UPDATE');

  const rectifications = await listByFilter('dp_rectification', [
    { fieldName: 'dp_rect_issue_id', operator: 'EQ', value: issueRectify.recordId },
  ]);
  if (rectifications.length > 0) {
    const rectId = String(rectifications[0].id ?? rectifications[0].pid);
    await execCommand('dp:start_rectification', {}, rectId, 'STATE_TRANSITION');
    await execCommand('dp:submit_rectification', {
      dp_rect_result: '已补装防护罩并完成点检',
      dp_rect_evidence: `${batchTag}-photo-proof`,
    }, rectId, 'STATE_TRANSITION');
    await execCommand('dp:accept_rectification', {
      dp_rect_accept_remark: '现场复核通过，整改闭环',
    }, rectId, 'STATE_TRANSITION');
  }

  const issueNoAction = await execCommand('dp:create_issue', {
    dp_issue_title: `临时警示牌破损 ${batchTag}`,
    dp_issue_content: '北门口一处警示牌褪色，当前无直接风险',
    dp_issue_area: '北门入口',
    dp_issue_source: 'DAILY_INSPECTION',
    dp_issue_project_id: projects.C,
    dp_issue_hazard_source_id: hazard2.recordId,
  });
  await execCommand('dp:submit_issue', {}, issueNoAction.recordId, 'STATE_TRANSITION');
  await execCommand('dp:triage_issue', {
    dp_triage_decision: 'NO_ACTION',
    dp_triage_remark: '纳入下一批常规维护统一处理',
  }, issueNoAction.recordId, 'UPDATE');

  const issueInspection = await execCommand('dp:create_issue', {
    dp_issue_title: `炸药库传感器告警 ${batchTag}`,
    dp_issue_content: '传感器数据波动异常，需要专项巡检',
    dp_issue_area: '炸药库',
    dp_issue_source: 'DAILY_INSPECTION',
    dp_issue_project_id: projects.A,
    dp_issue_hazard_source_id: hazard1.recordId,
  });
  await execCommand('dp:submit_issue', {}, issueInspection.recordId, 'STATE_TRANSITION');
  await execCommand('dp:triage_issue', {
    dp_triage_decision: 'CREATE_INSPECTION',
    dp_hazard_level: 'MEDIUM',
    dp_hazard_factor: 'ENVIRONMENT',
    dp_triage_remark: '需要生成专项巡检任务',
  }, issueInspection.recordId, 'UPDATE');

  const tasks = await listByFilter('dp_inspection_task', [
    { fieldName: 'dp_task_issue_id', operator: 'EQ', value: issueInspection.recordId },
  ]);
  if (tasks.length > 0) {
    const taskId = String(tasks[0].id ?? tasks[0].pid);
    await execCommand('dp:start_inspection', {}, taskId, 'STATE_TRANSITION');
    await execCommand('dp:complete_inspection', {
      dp_task_result: '已紧固传感器线缆并完成基线标定',
      dp_task_evidence: `${batchTag}-巡检记录`,
      dp_task_actual_date: dateOffset(0),
    }, taskId, 'STATE_TRANSITION');
    summary.inspectionTasks.push({ id: taskId, status: 'COMPLETED' });
  }

  summary.issues.push(
    { flow: 'NEED_RECTIFY', id: issueRectify.recordId },
    { flow: 'NO_ACTION', id: issueNoAction.recordId },
    { flow: 'CREATE_INSPECTION', id: issueInspection.recordId },
  );

  const standard = await execCommand('dp:create_standard', {
    dp_qs_code: `QS-${uid('STD').slice(-6)}`,
    dp_qs_name: `破碎机振动限值 ${batchTag}`,
    dp_qs_category: 'STRUCTURE',
    dp_qs_description: 'RMS 振动值应低于 4.5 mm/s',
    dp_qs_version: 'v1.0',
  });
  summary.qualityStandards.push({ id: standard.recordId });

  const checkpointPassed = await execCommand('dp:create_checkpoint', {
    dp_qc_project_id: projects.A,
    dp_qc_name: `联调联试质量门 ${batchTag}`,
    dp_qc_category: 'STRUCTURE',
    dp_qc_standard: standard.recordId,
    dp_qc_inspector: '杨帆',
    dp_qc_inspection_date: dateOffset(-1),
    dp_qc_remark: '联调前首次质量检查',
  });
  await execCommand('dp:pass_checkpoint', {}, checkpointPassed.recordId, 'STATE_TRANSITION');
  summary.qualityCheckpoints.push({ id: checkpointPassed.recordId, result: 'PASSED' });

  const checkpointConditional = await execCommand('dp:create_checkpoint', {
    dp_qc_project_id: projects.B,
    dp_qc_name: `电气回路检查 ${batchTag}`,
    dp_qc_category: 'MEP',
    dp_qc_standard: standard.recordId,
    dp_qc_inspector: '许斌',
    dp_qc_inspection_date: dateOffset(-1),
    dp_qc_remark: '线缆绑扎细节待优化',
  });
  await execCommand('dp:conditional_pass', { dp_qc_remark: '允许先运行，48小时内完成整改' }, checkpointConditional.recordId, 'STATE_TRANSITION');
  summary.qualityCheckpoints.push({ id: checkpointConditional.recordId, result: 'CONDITIONAL' });

  const checkpointFailed = await execCommand('dp:create_checkpoint', {
    dp_qc_project_id: projects.C,
    dp_qc_name: `包装线成品检查 ${batchTag}`,
    dp_qc_category: 'FINISH',
    dp_qc_standard: standard.recordId,
    dp_qc_inspector: '周杰',
    dp_qc_inspection_date: dateOffset(-1),
    dp_qc_remark: '表面平整度不达标',
  });
  await execCommand('dp:fail_checkpoint', { dp_qc_remark: '需返工后再申请验收' }, checkpointFailed.recordId, 'STATE_TRANSITION');
  summary.qualityCheckpoints.push({ id: checkpointFailed.recordId, result: 'FAILED' });

  // 8) Annual plan
  let annualPlanId = '';
  const planName = `2026年度生产计划 ${batchTag}`;
  for (let y = 2026; y <= 2050; y++) {
    const r = await execCommand('ap:create_annual_plan', {
      ap_project_id: projects.A,
      ap_stat_year: y,
      ap_plan_name: planName,
    }, undefined, undefined, { allowFailure: true });

    if (r.ok) {
      annualPlanId = r.recordId;
      break;
    }
  }
  if (!annualPlanId) {
    throw new Error('Failed to create annual plan for all years 2026-2050');
  }

  const subPlans = await listByFilter('ap_sub_plan', [
    { fieldName: 'ap_annual_plan_id', operator: 'EQ', value: annualPlanId },
  ]);
  if (subPlans.length > 0) {
    const subPlanId = String(subPlans[0].id ?? subPlans[0].pid);
    await execCommand('ap:add_work_package', {
      ap_sub_plan_id: subPlanId,
      ap_wp_name: `破碎产线扩容工作包 ${batchTag}`,
      ap_wp_category: 'BUILDING',
      ap_wp_remark: '年度产能提升核心工作包',
    });
  }

  await transitionAnnualPlan('ap:submit_annual_plan', annualPlanId, '演示脚本自动提交');
  await transitionAnnualPlan('ap:approve_annual_plan', annualPlanId, '演示脚本自动审批');
  summary.annualPlans.push({ id: annualPlanId, name: planName, status: 'APPROVED' });

  // 9) Documents & knowledge base
  const category = await execCommand('dk:create_category', {
    dk_cat_name: `运营作业手册 ${batchTag}`,
    dk_cat_code: `OPS_${uid('CAT').slice(-6)}`,
    dk_cat_description: '用于存放作业流程、报告与制度文件',
    dk_cat_sort_order: 10,
  });
  summary.docCategories.push({ id: category.recordId });

  const docPublic = await execCommand('dk:create_document', {
    dk_doc_title: `日常运行指导书 ${batchTag}`,
    dk_doc_project_id: projects.A,
    dk_doc_category_id: category.recordId,
    dk_doc_type: 'REPORT',
    dk_doc_version: 'v1.0',
    dk_doc_abstract: '班组交接与设备开停机执行规范',
    dk_doc_content: '包含开机前检查、运行监控、停机复核等标准步骤',
    dk_doc_tags: `指导,运行,${batchTag}`,
    dk_doc_access_level: 'PUBLIC',
  });
  await execCommand('dk:publish_document', {}, docPublic.recordId, 'STATE_TRANSITION');

  const docConfidential = await execCommand('dk:create_document', {
    dk_doc_title: `事故复盘报告 ${batchTag}`,
    dk_doc_project_id: projects.B,
    dk_doc_category_id: category.recordId,
    dk_doc_type: 'REPORT',
    dk_doc_version: 'v2.0',
    dk_doc_abstract: '事故根因分析与整改措施',
    dk_doc_content: '详细复盘经过、责任归因与预防方案',
    dk_doc_tags: `事故,复盘,${batchTag}`,
    dk_doc_access_level: 'CONFIDENTIAL',
  });
  await execCommand('dk:publish_document', {}, docConfidential.recordId, 'STATE_TRANSITION');
  await execCommand('dk:archive_document', {}, docConfidential.recordId, 'STATE_TRANSITION');

  summary.documents.push(
    { id: docPublic.recordId, accessLevel: 'PUBLIC', status: 'PUBLISHED' },
    { id: docConfidential.recordId, accessLevel: 'CONFIDENTIAL', status: 'ARCHIVED' },
  );

  const version = await execCommand('dk:create_version', {
    dk_ver_document_id: docPublic.recordId,
    dk_ver_number: 'v1.1',
    dk_ver_change_summary: '新增检修前上锁挂牌步骤',
    dk_ver_content_snapshot: `版本快照 ${batchTag}`,
  });
  summary.docVersions.push({ id: version.recordId, documentId: docPublic.recordId });

  const article = await execCommand('dk:create_article', {
    dk_ka_title: `最佳实践：输送机维护 ${batchTag}`,
    dk_ka_category_id: category.recordId,
    dk_ka_content: '沉淀周度点检清单与关键风险控制点',
    dk_ka_tags: `最佳实践,维护,${batchTag}`,
  });
  await execCommand('dk:publish_article', {}, article.recordId, 'STATE_TRANSITION');
  await execCommand('dk:archive_article', {}, article.recordId, 'STATE_TRANSITION');
  summary.articles.push({ id: article.recordId, status: 'ARCHIVED' });

  const link = await execCommand('dk:link_document', {
    dk_pd_project_id: projects.A,
    dk_pd_document_id: docPublic.recordId,
    dk_pd_upload_date: dateOffset(0),
    dk_pd_remark: `演示脚本自动关联 ${batchTag}`,
  });
  summary.projectDocLinks.push({ id: link.recordId, projectId: projects.A, documentId: docPublic.recordId });

  const outputPath = path.resolve(process.cwd(), OUTPUT_FILE);
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, JSON.stringify(summary, null, 2));

  console.log('[seed] Done. Summary:');
  console.log(JSON.stringify(summary, null, 2));
  console.log(`[seed] Result file: ${outputPath}`);

  await api.dispose();
}

main().catch((error) => {
  console.error('[seed] Failed:', error.message);
  process.exit(1);
});
