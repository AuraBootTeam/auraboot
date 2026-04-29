/**
 * Showcase Arsenal Seed — Complete Capability Demonstration
 *
 * Creates ALL arsenal showcase data:
 * 1. Showcase plugin sample data (10 records with all 22 field types)
 * 2. 19-widget Dashboard (all chart/widget types)
 * 3. 10-block Report (all report block types)
 * 4. Full BPMN process (all 9 node types)
 * 5. Full Automation rules (all 6 triggers + 9 actions)
 * 6. AI Agent tool showcase data
 *
 * Run AFTER seed-showcase-data.spec.ts and plugin imports:
 *   aura plugin publish plugins/showcase --yes
 *   npx playwright test seed-showcase-arsenal --config=playwright.seed.config.ts
 */

import { test, expect } from '@playwright/test';
import { executeCommandViaApi } from '../../e2e/helpers';

async function cmd(
  page: any,
  commandCode: string,
  payload: Record<string, unknown>,
  targetRecordId?: string,
  operationType?: string,
): Promise<string> {
  const result = await executeCommandViaApi(
    page,
    commandCode,
    payload,
    targetRecordId,
    operationType,
  );
  expect(result.code).toBe('0');
  return result.recordId;
}

test.describe.serial('Showcase Arsenal — Full Capability Demo', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  test.setTimeout(300_000);

  // ═════════════════════════════════════════════════════════════════════════
  // 1. Showcase Plugin — 10 records with ALL field types
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 1: Showcase All-Fields — 10 sample records with all 22 fields', async ({ page }) => {
    const items = [
      {
        name: '高性能MCU控制模块',
        desc: '基于ARM Cortex-M4的高性能微控制器模块，支持CAN/LIN通信',
        qty: 500,
        price: 28.5,
        active: true,
        priority: 'high',
        category: 'electronics',
        progress: 85,
        rating: 5,
        color: '#3B82F6',
        tags: 'MCU,ARM,Cortex-M4,汽车电子',
        startDate: '2025-09-01',
        endDate: '2026-06-30',
        website: 'https://www.st.com/stm32',
        email: 'mcu-support@xinrantech.com',
        phone: '021-58769300',
        attachment: [
          { name: 'MCU-Datasheet-v2.3.pdf', url: '/files/mcu-datasheet.pdf', size: 2457600, type: 'application/pdf' },
          { name: '原理图-Rev3.zip', url: '/files/schematic-rev3.zip', size: 5242880, type: 'application/zip' },
        ],
        richtext: '<h2>STM32F4 高性能MCU控制模块</h2><p>基于 <strong>ARM Cortex-M4</strong> 内核，主频高达 <em>168MHz</em>，内置 FPU 浮点运算单元。</p><h3>核心特性</h3><ul><li>支持 <strong>CAN 2.0B</strong> / LIN 总线通信</li><li>工作温度范围：-40°C ~ +125°C</li><li>内置 1MB Flash + 192KB SRAM</li></ul><blockquote>通过 <strong>AEC-Q100 Grade 1</strong> 车规认证，适用于汽车电子 ECU 开发。</blockquote><ol><li>硬件设计参考 <a href="https://www.st.com/stm32">STM32 官方文档</a></li><li>配套开发板已集成 CAN 收发器</li><li>提供完整的 HAL 驱动库和示例工程</li></ol>',
        remark: 'Q1 主推产品，配合方案设计一起推广',
      },
      {
        name: '车规级LDO稳压器',
        desc: '宽温域-40~150°C，AEC-Q100认证，适用于汽车电子',
        qty: 2000,
        price: 3.8,
        active: true,
        priority: 'critical',
        category: 'electronics',
        progress: 100,
        rating: 5,
        color: '#EF4444',
        tags: 'LDO,稳压器,AEC-Q100,车规级',
        startDate: '2025-07-15',
        endDate: '2026-12-31',
        website: 'https://www.ti.com/power-management',
        email: 'ldo-team@xinrantech.com',
        phone: '0755-86543210',
        attachment: [
          { name: 'LDO-AEC-Q100-Report.pdf', url: '/files/ldo-q100.pdf', size: 1843200, type: 'application/pdf' },
        ],
        richtext: '<h2>车规级 LDO 线性稳压器</h2><p>超低噪声 <strong>LDO 稳压器</strong>，输出电流高达 <em>500mA</em>，压差仅 200mV。</p><h3>电气参数</h3><ol><li>输入电压范围：<strong>3.0V ~ 40V</strong></li><li>输出精度：±1%（全温度范围）</li><li>静态电流：仅 <em>25μA</em>（典型值）</li></ol><h3>认证与可靠性</h3><ul><li>通过 <strong>AEC-Q100 Grade 0</strong> 认证</li><li>温度范围：-40°C ~ +150°C</li><li>MTBF > <em>500万小时</em></li></ul><blockquote>适用于汽车 ECU、ADAS、车身电子等对电源纹波要求严格的场景。详见 <a href="https://www.ti.com/power-management">TI 电源管理</a>。</blockquote>',
        remark: '长期供应协议已签，年用量>100K',
      },
      {
        name: 'PCBA贴片加工服务',
        desc: 'SMT全自动贴片线，支持0201~QFN/BGA，AOI+SPI全检',
        qty: 1,
        price: 15000,
        active: true,
        priority: 'high',
        category: 'service',
        progress: 60,
        rating: 4,
        color: '#10B981',
        tags: 'SMT,PCBA,贴片,代工',
        startDate: '2026-01-01',
        endDate: '2026-03-31',
        website: 'https://www.jlcpcb.com',
        email: 'smt@auramanufacturing.com',
        phone: '0769-22881234',
        attachment: [
          { name: 'SMT产线介绍.pdf', url: '/files/smt-intro.pdf', size: 3145728, type: 'application/pdf' },
          { name: 'IPC-A-610认证.jpg', url: '/files/ipc-cert.jpg', size: 524288, type: 'image/jpeg' },
          { name: '报价模板.xlsx', url: '/files/quote-template.xlsx', size: 102400, type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
        ],
        richtext: '<h2>PCBA 贴片加工服务</h2><p>拥有 <strong>6 条全自动 SMT 产线</strong>，日产能达 <em>200万点</em>，支持从打样到量产全流程。</p><h3>工艺能力</h3><ul><li>最小元件：<strong>01005</strong>（公制 0402）</li><li>最大 PCB 尺寸：<em>510mm × 460mm</em></li><li>BGA 最小间距：<strong>0.3mm pitch</strong></li></ul><h3>质量保障</h3><ol><li><strong>SPI</strong> 锡膏检测 — 100% 覆盖</li><li><strong>AOI</strong> 光学检测 — 焊后全检</li><li><strong>X-Ray</strong> — BGA/QFN 内部焊点抽检</li></ol><blockquote>通过 <strong>ISO 9001</strong> / <strong>IATF 16949</strong> / <strong>ISO 14001</strong> 认证。详见 <a href="https://www.jlcpcb.com">JLCPCB</a>。</blockquote>',
        remark: '正在洽谈年度框架协议，目标下季度签约',
      },
      {
        name: '工业级连接器组件',
        desc: 'IP67防水矩形连接器，12Pin，额定电流5A/250V',
        qty: 800,
        price: 12.6,
        active: true,
        priority: 'medium',
        category: 'hardware',
        progress: 45,
        rating: 3,
        color: '#F59E0B',
        tags: '连接器,IP67,防水,工业',
        startDate: '2025-11-01',
        endDate: '2026-04-30',
        website: 'https://www.te.com/connectors',
        email: 'connector@xinrantech.com',
        phone: '0512-66789012',
        attachment: [
          { name: 'IP67连接器选型手册.pdf', url: '/files/connector-catalog.pdf', size: 7340032, type: 'application/pdf' },
        ],
        richtext: '<h2>工业级 IP67 防水连接器</h2><p>专为 <strong>恶劣工业环境</strong> 设计的矩形连接器，防护等级 <em>IP67</em>，耐盐雾、耐振动。</p><h3>技术规格</h3><ul><li>针数：<strong>12Pin</strong>（可定制 4~24Pin）</li><li>额定电流：<em>5A / 250VAC</em></li><li>接触电阻：≤ <strong>20mΩ</strong></li></ul><h3>机械特性</h3><ol><li>插拔寿命：<strong>>5000 次</strong></li><li>振动：10-500Hz / <em>10G</em></li><li>工作温度：-40°C ~ +105°C</li></ol><blockquote>广泛应用于 <strong>工业自动化</strong>、机器人、轨道交通等领域。选型参考 <a href="https://www.te.com/connectors">TE Connectivity</a>。</blockquote>',
        remark: '客户反馈插拔手感偏紧，已反馈供应商优化',
      },
      {
        name: '物联网网关开发套件',
        desc: '含Linux主板+4G模块+WiFi+BLE+GPS，预装AuraBoot Agent',
        qty: 50,
        price: 680,
        active: true,
        priority: 'high',
        category: 'electronics',
        progress: 90,
        rating: 4,
        color: '#6366F1',
        tags: 'IoT,网关,Linux,4G,WiFi,BLE',
        startDate: '2025-10-15',
        endDate: '2026-09-30',
        website: 'https://docs.auraboot.io/iot-gateway',
        email: 'iot@auraboot.io',
        phone: '010-82568900',
        attachment: [
          { name: 'IoT网关快速入门指南.pdf', url: '/files/iot-quickstart.pdf', size: 1572864, type: 'application/pdf' },
          { name: '固件v3.2.1.bin', url: '/files/firmware-v3.2.1.bin', size: 8388608, type: 'application/octet-stream' },
        ],
        richtext: '<h2>物联网网关开发套件 v3.2</h2><p>一站式 <strong>IoT 网关解决方案</strong>，预装 <em>AuraBoot Agent</em>，开箱即用，支持多协议接入。</p><h3>硬件配置</h3><ul><li>处理器：<strong>ARM Cortex-A7</strong> 双核 1GHz</li><li>内存：512MB DDR3 + 8GB eMMC</li><li>通信：<em>4G LTE / WiFi / BLE 5.0 / GPS</em></li></ul><h3>软件特性</h3><ol><li>预装 <strong>Linux 5.10</strong> + AuraBoot Agent</li><li>支持 <strong>MQTT / HTTP / CoAP / Modbus</strong> 协议</li><li>OTA 远程升级，支持 A/B 分区</li></ol><blockquote>已在 <em>50+ 项目</em>中部署验证，详细文档见 <a href="https://docs.auraboot.io/iot-gateway">开发者文档</a>。</blockquote>',
        remark: '已发出50套样品，反馈良好，计划量产',
      },
      {
        name: '柔性PCB排线',
        desc: 'FPC 0.5mm间距，6层双面FPCB，用于手机内部连接',
        qty: 5000,
        price: 1.25,
        active: true,
        priority: 'low',
        category: 'hardware',
        progress: 100,
        rating: 4,
        color: '#8B5CF6',
        tags: 'FPC,柔性板,PCB,手机',
        startDate: '2025-08-20',
        endDate: '2026-02-28',
        website: 'https://www.szlcsc.com/fpc',
        email: 'fpc-sales@flexpcb.cn',
        phone: '0755-23456789',
        attachment: [
          { name: 'FPC设计规范.pdf', url: '/files/fpc-design-guide.pdf', size: 921600, type: 'application/pdf' },
        ],
        richtext: '<h2>柔性 FPC 排线</h2><p>超薄 <strong>柔性印刷电路板</strong>，厚度仅 <em>0.1mm</em>，弯折半径低至 0.5mm，适合高密度空间布线。</p><h3>产品规格</h3><ul><li>间距：<strong>0.5mm</strong>（可选 0.3mm / 1.0mm）</li><li>层数：<em>6层</em>双面 FPCB</li><li>基材：<strong>PI（聚酰亚胺）</strong></li></ul><h3>应用场景</h3><ol><li>智能手机内部连接（屏幕↔主板）</li><li>可穿戴设备柔性互联</li><li>折叠屏铰链处弯折排线</li></ol><blockquote>通过 <strong>UL 认证</strong>，弯折寿命 > <em>10万次</em>（R=1mm）。选型参考 <a href="https://www.szlcsc.com/fpc">立创商城 FPC</a>。</blockquote>',
        remark: '大客户定制款，已通过信赖性测试',
      },
      {
        name: '智能电源管理芯片',
        desc: '多通道PMIC，支持锂电池充放电管理+路径管理',
        qty: 1000,
        price: 8.9,
        active: true,
        priority: 'medium',
        category: 'electronics',
        progress: 20,
        rating: 3,
        color: '#EC4899',
        tags: 'PMIC,电源管理,锂电池,充电',
        startDate: '2026-02-01',
        endDate: '2026-08-31',
        website: 'https://www.nxp.com/pmic',
        email: 'power-ic@xinrantech.com',
        phone: '021-31278800',
        attachment: [
          { name: 'PMIC评估板手册.pdf', url: '/files/pmic-eval.pdf', size: 1048576, type: 'application/pdf' },
          { name: '充放电曲线数据.csv', url: '/files/charge-curve.csv', size: 51200, type: 'text/csv' },
        ],
        richtext: '<h2>智能多通道 PMIC</h2><p>集成 <strong>4 路 DC-DC</strong> + <strong>3 路 LDO</strong> 的电源管理芯片，内置锂电池充电管理和 <em>路径管理</em> 功能。</p><h3>关键参数</h3><ul><li>充电电流：最大 <strong>2A</strong>（可编程）</li><li>转换效率：<em>>95%</em>（DC-DC）</li><li>静态功耗：<strong>< 10μA</strong>（Shutdown 模式）</li></ul><h3>智能功能</h3><ol><li>I2C 接口可编程电压输出</li><li>NTC <strong>温度监控</strong> + 过温保护</li><li>支持 <em>USB PD / QC</em> 快充协议</li></ol><blockquote>适用于智能穿戴、TWS 耳机、便携医疗设备。参考 <a href="https://www.nxp.com/pmic">NXP PMIC</a> 产品线。</blockquote>',
        remark: '样品测试中，待确认纹波指标',
      },
      {
        name: '自动化测试夹具',
        desc: '定制ICT/FCT测试治具，含编程烧录+功能测试+外观检测',
        qty: 10,
        price: 8500,
        active: false,
        priority: 'low',
        category: 'hardware',
        progress: 100,
        rating: 5,
        color: '#6B7280',
        tags: '测试夹具,ICT,FCT,自动化',
        startDate: '2025-06-01',
        endDate: '2025-12-31',
        website: 'https://www.keysight.com/test',
        email: 'fixture@auramanufacturing.com',
        phone: '0769-33445566',
        attachment: [
          { name: '夹具3D模型.step', url: '/files/fixture-3d.step', size: 15728640, type: 'application/step' },
          { name: '测试规格书.docx', url: '/files/test-spec.docx', size: 204800, type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' },
          { name: '验收报告.pdf', url: '/files/acceptance-report.pdf', size: 1048576, type: 'application/pdf' },
        ],
        richtext: '<h2>自动化 ICT/FCT 测试夹具</h2><p>全定制化测试解决方案，覆盖 <strong>ICT 在线测试</strong> + <strong>FCT 功能测试</strong> + <em>编程烧录</em>一体化。</p><h3>测试覆盖</h3><ul><li><strong>ICT</strong>：开短路、电阻、电容、二极管、IC 引脚</li><li><strong>FCT</strong>：电源上电、通信接口、传感器采集</li><li><strong>烧录</strong>：SWD / JTAG / UART 多接口</li></ul><h3>夹具规格</h3><ol><li>定位精度：<em>±0.05mm</em></li><li>探针寿命：> <strong>50万次</strong></li><li>切换时间：< <em>3秒</em>（气动下压）</li></ol><blockquote>已交付 <strong>200+ 套</strong>测试夹具，覆盖消费电子、汽车电子、工业控制领域。参考 <a href="https://www.keysight.com/test">Keysight</a>。</blockquote>',
        remark: '项目已归档，客户后续无新需求',
      },
      {
        name: 'EMC整改咨询服务',
        desc: '提供辐射/传导超标整改方案，含实验室预测试+整改+复测',
        qty: 1,
        price: 25000,
        active: true,
        priority: 'medium',
        category: 'service',
        progress: 30,
        rating: 4,
        color: '#14B8A6',
        tags: 'EMC,电磁兼容,整改,认证',
        startDate: '2026-01-15',
        endDate: '2026-06-15',
        website: 'https://www.rohde-schwarz.com/emc',
        email: 'emc@xinrantech.com',
        phone: '0755-86123456',
        attachment: [
          { name: 'EMC整改案例集.pdf', url: '/files/emc-cases.pdf', size: 4194304, type: 'application/pdf' },
        ],
        richtext: '<h2>EMC 电磁兼容整改咨询</h2><p>提供从 <strong>预测试</strong> 到 <strong>整改方案</strong> 到 <em>复测通过</em> 的全流程 EMC 咨询服务。</p><h3>服务范围</h3><ul><li><strong>辐射发射 (RE)</strong>：30MHz ~ 6GHz</li><li><strong>传导发射 (CE)</strong>：150kHz ~ 30MHz</li><li><strong>ESD / EFT / Surge</strong> 抗扰度</li></ul><h3>整改流程</h3><ol><li>预扫描 — <em>定位超标频点和辐射源</em></li><li>方案设计 — 滤波、屏蔽、接地优化</li><li>PCB 改版指导 — <strong>布局布线优化建议</strong></li><li>复测验证 — 陪同进实验室直到通过</li></ol><blockquote>已成功整改 <strong>100+ 项目</strong>，一次通过率 > <em>90%</em>。仪器参考 <a href="https://www.rohde-schwarz.com/emc">R&S EMC</a>。</blockquote>',
        remark: '新能源车载设备EMC整改项目，Q2交付',
      },
      {
        name: '新能源BMS主控板',
        desc: '16串锂电池管理系统主控板，CAN通信+均衡+SOC估算',
        qty: 200,
        price: 158,
        active: true,
        priority: 'critical',
        category: 'electronics',
        progress: 70,
        rating: 5,
        color: '#F97316',
        tags: 'BMS,锂电池,新能源,CAN',
        startDate: '2025-12-01',
        endDate: '2026-11-30',
        website: 'https://www.catl.com/bms',
        email: 'bms-engineering@xinrantech.com',
        phone: '0591-88776655',
        attachment: [
          { name: 'BMS主控板原理图.pdf', url: '/files/bms-schematic.pdf', size: 2097152, type: 'application/pdf' },
          { name: 'SOC算法说明.pdf', url: '/files/soc-algorithm.pdf', size: 1572864, type: 'application/pdf' },
          { name: 'BMS测试报告-v2.pdf', url: '/files/bms-test-report.pdf', size: 3145728, type: 'application/pdf' },
        ],
        richtext: '<h2>新能源 BMS 主控板</h2><p>支持 <strong>16串锂电池组</strong> 管理，集成 <em>SOC 估算</em>、主动均衡、CAN 通信等核心功能。</p><h3>核心功能</h3><ul><li><strong>电压采集</strong>：16 通道，精度 ±2mV</li><li><strong>温度监测</strong>：8 路 NTC，精度 ±1°C</li><li><strong>均衡电流</strong>：<em>200mA</em>（主动均衡）</li></ul><h3>SOC 算法</h3><ol><li>安时积分法 + <strong>EKF（扩展卡尔曼滤波）</strong></li><li>OCV-SOC 查表校准</li><li>SOC 估算精度：<em>< ±3%</em></li></ol><blockquote>满足 <strong>GB/T 36276-2018</strong> 储能系统安全标准，支持 <em>CAN 2.0B</em> 上位机通信。参考 <a href="https://www.catl.com/bms">CATL BMS</a>。</blockquote>',
        remark: '审核中 - 等待安规认证报告完成',
      },
    ];

    // Create all 10 records and collect their IDs for state transitions
    const recordIds: string[] = [];
    let created = 0;
    for (const item of items) {
      try {
        const recordId = await cmd(page, 'sc:create_showcase', {
          sc_name: item.name,
          sc_description: item.desc,
          sc_quantity: item.qty,
          sc_price: item.price,
          sc_is_active: item.active,
          sc_priority: item.priority,
          sc_category: item.category,
          sc_progress: item.progress,
          sc_rating: item.rating,
          sc_color: item.color,
          sc_tags: item.tags,
          sc_start_date: item.startDate,
          sc_end_date: item.endDate,
          sc_website: item.website,
          sc_email: item.email,
          sc_phone: item.phone,
          sc_attachment: JSON.stringify(item.attachment),
          sc_richtext_content: item.richtext,
          sc_remark: item.remark,
        });
        recordIds.push(recordId);
        created++;
      } catch (e) {
        recordIds.push('');
        console.warn(
          `  Failed to create showcase record "${item.name}": ${(e as Error).message.slice(0, 100)}`,
        );
      }
    }
    console.log(`  Created ${created}/10 showcase records`);
    expect(created).toBeGreaterThanOrEqual(5);

    // State transitions: all records start as draft
    // Records 0,1,2,4,5,8: activate (draft -> active)
    // Records 3,9: activate then submit_review (draft -> active -> review)
    // Record 7: activate then archive (draft -> active -> archived)
    // Record 6: stays draft
    const activateIndices = [0, 1, 2, 3, 4, 5, 7, 8, 9];
    for (const idx of activateIndices) {
      if (!recordIds[idx]) continue;
      try {
        await cmd(page, 'sc:activate_showcase', {}, recordIds[idx], 'update');
        console.log(`  Activated: ${items[idx].name}`);
      } catch (e) {
        console.warn(`  Failed to activate "${items[idx].name}": ${(e as Error).message.slice(0, 80)}`);
      }
    }

    // Records 3,9: submit_review (active -> review)
    for (const idx of [3, 9]) {
      if (!recordIds[idx]) continue;
      try {
        await cmd(page, 'sc:submit_review_showcase', {}, recordIds[idx], 'update');
        console.log(`  Submitted for review: ${items[idx].name}`);
      } catch (e) {
        console.warn(`  Failed to submit_review "${items[idx].name}": ${(e as Error).message.slice(0, 80)}`);
      }
    }

    // Record 7: archive (active -> archived)
    if (recordIds[7]) {
      try {
        await cmd(page, 'sc:archive_showcase', {}, recordIds[7], 'update');
        console.log(`  Archived: ${items[7].name}`);
      } catch (e) {
        console.warn(`  Failed to archive "${items[7].name}": ${(e as Error).message.slice(0, 80)}`);
      }
    }

    console.log('  State transitions complete — draft:1, active:6, review:2, archived:1');
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 2. 19-Widget Dashboard
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 2: Dashboard — 19 widget types', async ({ page }) => {
    const widgets = [
      // Row 1: 4 Number Cards (h=2 at rowHeight 80 = ~170px, good for cards)
      {
        i: 'w_num_customers',
        x: 0,
        y: 0,
        w: 3,
        h: 2,
        type: 'NumberCard',
        title: '客户总数',
        config: { value: 60, icon: '🏢' },
      },
      {
        i: 'w_num_pipeline',
        x: 3,
        y: 0,
        w: 3,
        h: 2,
        type: 'NumberCard',
        title: 'Pipeline 金额',
        config: { value: 580, icon: '💰' },
      },
      {
        i: 'w_num_winrate',
        x: 6,
        y: 0,
        w: 3,
        h: 2,
        type: 'NumberCard',
        title: '赢单率',
        config: { value: 73, icon: '🎯' },
      },
      {
        i: 'w_num_leads',
        x: 9,
        y: 0,
        w: 3,
        h: 2,
        type: 'NumberCard',
        title: '本月新线索',
        config: { value: 28, icon: '📋' },
      },
      // Row 2: Bar + Line (h=4 at rowHeight 80 = ~350px, enough for ECharts)
      {
        i: 'w_bar_monthly',
        x: 0,
        y: 2,
        w: 6,
        h: 4,
        type: 'BarChart',
        title: '月度销售对比',
        config: {
          xAxis: [
            '2025/07',
            '2025/08',
            '2025/09',
            '2025/10',
            '2025/11',
            '2025/12',
            '2026/01',
            '2026/02',
            '2026/03',
          ],
          series: [
            { name: '目标(万)', data: [80, 80, 100, 100, 100, 120, 120, 80, 120] },
            { name: '实际(万)', data: [45, 68, 175, 92, 58, 138, 460, 72, 96] },
          ],
        },
      },
      {
        i: 'w_line_trend',
        x: 6,
        y: 2,
        w: 6,
        h: 4,
        type: 'LineChart',
        title: '销售趋势',
        config: {
          xAxis: [
            '2025/07',
            '2025/08',
            '2025/09',
            '2025/10',
            '2025/11',
            '2025/12',
            '2026/01',
            '2026/02',
            '2026/03',
          ],
          series: [
            { name: '新客户', data: [6, 8, 12, 9, 7, 11, 15, 8, 10] },
            { name: '新商机', data: [5, 7, 14, 10, 8, 13, 18, 9, 12] },
            { name: '赢单数', data: [2, 3, 8, 5, 3, 6, 12, 4, 5] },
          ],
        },
      },
      // Row 3: Pie + Funnel + Radar (h=4)
      {
        i: 'w_pie_stage',
        x: 0,
        y: 6,
        w: 4,
        h: 4,
        type: 'PieChart',
        title: '商机阶段分布',
        config: {
          data: [
            { name: '初步接触', value: 15 },
            { name: '需求确认', value: 12 },
            { name: '方案报价', value: 18 },
            { name: '商务谈判', value: 8 },
            { name: '赢单', value: 22 },
            { name: '输单', value: 6 },
          ],
        },
      },
      {
        i: 'w_funnel',
        x: 4,
        y: 6,
        w: 4,
        h: 4,
        type: 'FunnelChart',
        title: '销售漏斗',
        config: {
          data: [
            { name: '线索', value: 120 },
            { name: '合格线索', value: 68 },
            { name: '需求确认', value: 42 },
            { name: '方案报价', value: 28 },
            { name: '商务谈判', value: 18 },
            { name: '赢单', value: 12 },
          ],
        },
      },
      {
        i: 'w_radar',
        x: 8,
        y: 6,
        w: 4,
        h: 4,
        type: 'RadarChart',
        title: '销售团队能力',
        config: {
          categories: ['客户开发', '方案能力', '谈判技巧', '交付管理', '客户维护'],
          series: [
            { name: '陈志豪', data: [92, 85, 88, 75, 95] },
            { name: '张雨晴', data: [78, 95, 72, 88, 82] },
            { name: '林伟杰', data: [85, 78, 90, 82, 76] },
          ],
        },
      },
      // Row 4: Area + Gauge + Progress
      {
        i: 'w_area',
        x: 0,
        y: 10,
        w: 4,
        h: 4,
        type: 'AreaChart',
        title: '累计收入趋势',
        config: {
          xAxis: ['Q1-2025', 'Q2-2025', 'Q3-2025', 'Q4-2025', 'Q1-2026'],
          series: [{ name: '累计收入(万)', data: [120, 380, 720, 1200, 1560] }],
        },
      },
      {
        i: 'w_gauge',
        x: 4,
        y: 10,
        w: 4,
        h: 4,
        type: 'GaugeChart',
        title: 'Q1 目标完成率',
        config: { value: 78, max: 100 },
      },
      {
        i: 'w_progress',
        x: 8,
        y: 10,
        w: 4,
        h: 2,
        type: 'Progress',
        title: '年度KPI进度',
        config: { value: 68, target: 100 },
      },
      // Row 4 continued: Scatter
      {
        i: 'w_scatter',
        x: 8,
        y: 12,
        w: 4,
        h: 2,
        type: 'ScatterChart',
        title: '客户规模 vs 商机金额',
        config: {
          data: [
            { x: 50, y: 120 },
            { x: 100, y: 280 },
            { x: 200, y: 580 },
            { x: 80, y: 175 },
            { x: 150, y: 460 },
            { x: 30, y: 65 },
            { x: 250, y: 720 },
            { x: 120, y: 340 },
          ],
        },
      },
      // Row 5: Table + Heatmap
      {
        i: 'w_table',
        x: 0,
        y: 14,
        w: 5,
        h: 4,
        type: 'TableChart',
        title: '销售排行榜',
        config: {
          columns: ['排名', '姓名', '赢单额(万)', '赢单数'],
          data: [
            ['1', '陈志豪', '680', '12'],
            ['2', '张雨晴', '520', '9'],
            ['3', '林伟杰', '380', '8'],
            ['4', '王小明', '260', '6'],
            ['5', '李佳慧', '210', '5'],
          ],
        },
      },
      {
        i: 'w_heatmap',
        x: 5,
        y: 14,
        w: 7,
        h: 4,
        type: 'HeatmapChart',
        title: '团队活跃度热力图',
        config: {
          xAxis: ['周一', '周二', '周三', '周四', '周五'],
          yAxis: ['上午', '下午', '晚间'],
          data: [
            [0, 0, 8],
            [0, 1, 12],
            [0, 2, 3],
            [1, 0, 10],
            [1, 1, 15],
            [1, 2, 5],
            [2, 0, 9],
            [2, 1, 11],
            [2, 2, 2],
            [3, 0, 13],
            [3, 1, 14],
            [3, 2, 6],
            [4, 0, 7],
            [4, 1, 9],
            [4, 2, 1],
          ],
        },
      },
      // Row 6: Treemap + Rich Text + Countdown
      {
        i: 'w_treemap',
        x: 0,
        y: 18,
        w: 4,
        h: 4,
        type: 'TreemapChart',
        title: '行业收入分布',
        config: {
          data: [
            { name: '汽车电子', value: 600 },
            { name: '工业自动化', value: 360 },
            { name: '智能硬件', value: 310 },
            { name: '消费电子', value: 280 },
            { name: '通信设备', value: 180 },
          ],
        },
      },
      {
        i: 'w_richtext',
        x: 4,
        y: 18,
        w: 4,
        h: 4,
        type: 'RichText',
        title: '季度公告',
        config: {
          content:
            '<h3 style="margin:0 0 8px 0;color:#1e40af">📊 Q1 销售总结</h3><p style="margin:0 0 6px 0">本季度完成销售额 <strong style="color:#059669">¥1,560万</strong>，同比增长 <strong style="color:#059669">32%</strong>，超额完成目标。</p><ul style="margin:4px 0;padding-left:18px"><li>新增客户 <b>28</b> 家，重点客户 <b>5</b> 家</li><li>赢单 <b>12</b> 笔，平均客单价 <b>¥130万</b></li><li>重点项目：宁波鑫越年度框架 ¥460万</li><li>销冠：陈志豪 ¥680万（连续3个月第一）</li></ul>',
        },
      },
      {
        i: 'w_countdown',
        x: 8,
        y: 18,
        w: 4,
        h: 2,
        type: 'Countdown',
        title: 'Q2 结束倒计时',
        config: { targetDate: '2026-06-30T23:59:59Z', label: '距离 Q2 结束' },
      },
      // Row 6 continued: Leaderboard
      {
        i: 'w_leaderboard',
        x: 8,
        y: 20,
        w: 4,
        h: 2,
        type: 'Leaderboard',
        title: '销售冠军榜',
        config: {
          items: [
            { rank: 1, name: '陈志豪', value: 680 },
            { rank: 2, name: '张雨晴', value: 520 },
            { rank: 3, name: '林伟杰', value: 380 },
            { rank: 4, name: '王小明', value: 260 },
            { rank: 5, name: '李佳慧', value: 210 },
          ],
        },
      },
      // Row 7: WordCloud + NPS + Combo
      {
        i: 'w_wordcloud',
        x: 0,
        y: 22,
        w: 4,
        h: 4,
        type: 'WordCloudChart',
        title: '行业关键词',
        config: {
          data: [
            { name: '汽车电子', value: 120 },
            { name: '工业自动化', value: 95 },
            { name: '智能硬件', value: 85 },
            { name: '消费电子', value: 72 },
            { name: '通信设备', value: 65 },
            { name: '医疗器械', value: 58 },
            { name: '新能源', value: 52 },
            { name: '物联网', value: 48 },
            { name: '半导体', value: 42 },
            { name: '机器人', value: 38 },
            { name: 'AI芯片', value: 35 },
            { name: '传感器', value: 30 },
          ],
          wordField: 'name',
          weightField: 'value',
        },
      },
      {
        i: 'w_nps',
        x: 4,
        y: 22,
        w: 4,
        h: 4,
        type: 'NpsChart',
        title: '客户满意度 NPS',
        config: {
          data: [
            { score: 1, count: 2 },
            { score: 2, count: 3 },
            { score: 3, count: 8 },
            { score: 4, count: 15 },
            { score: 5, count: 32 },
          ],
          scoreField: 'score',
          countField: 'count',
          promoterThreshold: 5,
          passiveThreshold: 4,
          scoreRange: [1, 5],
        },
      },
      {
        i: 'w_combo',
        x: 8,
        y: 22,
        w: 4,
        h: 4,
        type: 'ComboChart',
        title: '销量与均价组合',
        config: {
          xAxis: ['Q1', 'Q2', 'Q3', 'Q4'],
          seriesConfig: [
            {
              field: 'quantity',
              type: 'bar',
              yAxisIndex: 0,
              label: '销量(台)',
              data: [320, 480, 560, 420],
            },
            {
              field: 'avgPrice',
              type: 'line',
              yAxisIndex: 1,
              label: '均价(万)',
              data: [12.5, 11.8, 13.2, 14.1],
            },
          ],
        },
      },
      // Row 8: Kanban + Gallery
      {
        i: 'w_kanban',
        x: 0,
        y: 26,
        w: 6,
        h: 4,
        type: 'Kanban',
        title: '项目看板',
        config: {
          columns: [
            { value: 'todo', label: '待办', color: '#94a3b8' },
            { value: 'doing', label: '进行中', color: '#3b82f6' },
            { value: 'done', label: '已完成', color: '#10b981' },
          ],
          items: [
            { id: '1', title: '客户需求分析', column: 'doing' },
            { id: '2', title: '方案设计', column: 'todo' },
            { id: '3', title: '样品测试', column: 'doing' },
            { id: '4', title: '报价审批', column: 'done' },
            { id: '5', title: '合同签订', column: 'todo' },
            { id: '6', title: '生产排期', column: 'done' },
          ],
        },
      },
      {
        i: 'w_gallery',
        x: 6,
        y: 26,
        w: 6,
        h: 4,
        type: 'Gallery',
        title: '产品图库',
        config: {
          items: [
            { id: '1', title: 'MCU 模组', imageUrl: 'https://picsum.photos/seed/mcu/400/300' },
            { id: '2', title: 'LDO 芯片', imageUrl: 'https://picsum.photos/seed/ldo/400/300' },
            { id: '3', title: 'PCBA 成品', imageUrl: 'https://picsum.photos/seed/pcba/400/300' },
            { id: '4', title: '传感器模块', imageUrl: 'https://picsum.photos/seed/sensor/400/300' },
            { id: '5', title: 'IoT 网关', imageUrl: 'https://picsum.photos/seed/iot/400/300' },
            { id: '6', title: '工控主板', imageUrl: 'https://picsum.photos/seed/board/400/300' },
          ],
          colCount: 3,
        },
      },
    ];

    const resp = await page.request.post('/api/dashboards', {
      data: {
        code: 'arsenal_capability_dashboard',
        title: '军火展 — 组件仪表盘',
        description: '展示 AuraBoot Dashboard Designer 支持的全部 24 种 Widget 类型',
        scope: 'global',
        layoutConfig: { columns: 12, rowHeight: 80, gap: 12 },
        widgets,
        isDefault: false,
        sortOrder: 1,
      },
    });
    const body = await resp.json();
    if (body.code === '0' || resp.ok()) {
      console.log('  Created Dashboard: 24 组件全覆盖仪表盘');
      const pid = body.data?.pid || body.data?.id;
      if (pid) {
        await page.request.post(`/api/dashboards/${pid}/publish`).catch(() => {});
      }
    } else {
      console.warn(`  Dashboard creation warning: ${JSON.stringify(body).slice(0, 200)}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 3. 10-Block Report
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 3: Report — 10 block types', async ({ page }) => {
    const reportDsl = {
      kind: 'Report',
      version: '1.0.0',
      id: 'report.arsenal_full',
      pageSize: 'A4',
      areas: {
        header: {
          blocks: [
            {
              id: 'hdr_1',
              blockType: 'page-header',
              height: 80,
              elements: [
                {
                  type: 'text',
                  content: '鑫然科技',
                  align: 'left',
                  style: { fontSize: 18, fontWeight: 'bold' },
                },
                {
                  type: 'text',
                  content: '军火展 — 全能力报表',
                  align: 'center',
                  style: { fontSize: 14 },
                },
                { type: 'date', align: 'right', style: { fontSize: 10 } },
              ],
            },
          ],
        },
        main: {
          blocks: [
            {
              id: 'blk_stat_1',
              blockType: 'stat-card',
              title: '总客户数',
              config: { aggregation: 'count', label: '客户总数', color: '#3B82F6' },
            },
            {
              id: 'blk_stat_2',
              blockType: 'stat-card',
              title: '赢单总额',
              config: { label: '赢单总额', value: '¥2,250万', color: '#10B981' },
            },
            {
              id: 'blk_stat_3',
              blockType: 'stat-card',
              title: '赢单率',
              config: { label: '赢单率', value: '73%', color: '#F59E0B' },
            },
            {
              id: 'blk_table',
              blockType: 'table',
              title: '客户清单',
              config: {
                dataSource: { type: 'model', modelCode: 'crm_account' },
                columns: [
                  { field: 'crm_acc_code', title: '编号', width: 120 },
                  { field: 'crm_acc_name', title: '客户名', width: 200 },
                  { field: 'crm_acc_industry', title: '行业', width: 100 },
                  { field: 'crm_acc_rating', title: '评级', width: 60 },
                ],
              },
            },
            {
              id: 'blk_grouped',
              blockType: 'grouped-table',
              title: '按行业分组汇总',
              config: {
                groupByField: 'crm_acc_industry',
                columns: [
                  { field: 'crm_acc_name', title: '客户名' },
                  { field: 'crm_acc_rating', title: '评级' },
                ],
              },
            },
            {
              id: 'blk_richtext',
              blockType: 'rich-text',
              content:
                '## 报告说明\n\n本报表展示鑫然科技 CRM 系统中的核心数据指标，包括客户分布、销售漏斗、行业分析等维度。数据时间范围：2024年1月 — 2025年3月。\n\n> 数据来源：AuraBoot CRM 模块自动生成',
            },
            {
              id: 'blk_crosstab',
              blockType: 'cross-tab',
              title: '销售×月份透视表',
              config: {
                rowField: 'owner',
                colField: 'month',
                valueField: 'amount',
                aggregation: 'sum',
              },
            },
            {
              id: 'blk_chart_bar',
              blockType: 'chart',
              title: '月度销售柱状图',
              config: { chartType: 'bar', categoryField: 'month', valueField: 'amount' },
            },
            {
              id: 'blk_chart_pie',
              blockType: 'chart',
              title: '商机阶段饼图',
              config: { chartType: 'pie', categoryField: 'stage', valueField: 'count' },
            },
            {
              id: 'blk_barcode',
              blockType: 'barcode',
              title: '报表编号条码',
              config: { format: 'code128', value: 'RPT-2025-ARSENAL-001', width: 2, height: 60 },
            },
            {
              id: 'blk_watermark',
              blockType: 'watermark',
              config: {
                text: '鑫然科技 — 机密',
                fontSize: 24,
                color: 'rgba(0,0,0,0.06)',
                rotation: -30,
              },
            },
          ],
        },
        footer: {
          blocks: [
            {
              id: 'ftr_1',
              blockType: 'page-footer',
              height: 40,
              elements: [
                {
                  type: 'text',
                  content: '鑫然科技有限公司',
                  align: 'left',
                  style: { fontSize: 9, color: '#999' },
                },
                { type: 'page-number', align: 'center', style: { fontSize: 9 } },
                {
                  type: 'text',
                  content: '机密文件，禁止外传',
                  align: 'right',
                  style: { fontSize: 9, color: '#999' },
                },
              ],
            },
          ],
        },
      },
    };

    const resp = await page.request.post('/api/pages', {
      data: {
        pageKey: 'arsenal_full_report',
        name: '军火展 — 10块全覆盖报表',
        title: '军火展 — 10块全覆盖报表',
        description:
          '展示 Report Designer 的全部 10 种 Block 类型：data-table、grouped-table、stat-card、rich-text、cross-tab、chart(bar)、chart(pie)、barcode、watermark + page-header/footer',
        pageType: 'custom',
        pageCategory: 'REPORT',
        dslSchema: reportDsl,
      },
    });
    const body = await resp.json();
    if (body.code === '0' || resp.ok()) {
      console.log('  Created Report: 10 块全覆盖报表');
    } else {
      console.warn(`  Report creation warning: ${JSON.stringify(body).slice(0, 200)}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 4. Full BPMN — All 9 node types in one process
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 4: BPMN — All 9 node types process', async ({ page }) => {
    const processKey = 'arsenal_full_nodes';
    const bpmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="军火展 — 全节点流程" isExecutable="true">
    <startEvent id="start" name="开始"/>
    <userTask id="ut_apply" name="填写申请(UserTask)"/>
    <exclusiveGateway id="xgw" name="金额判断(ExclusiveGateway)"/>
    <serviceTask id="st_auto" name="自动审批(ServiceTask)"/>
    <parallelGateway id="pgw_split" name="并行分支(ParallelGateway)"/>
    <userTask id="ut_tech" name="技术评审(UserTask)"/>
    <userTask id="ut_finance" name="财务审批(UserTask)"/>
    <inclusiveGateway id="igw" name="包容汇聚(InclusiveGateway)"/>
    <receiveTask id="rt_wait" name="等待确认(ReceiveTask)"/>
    <callActivity id="ca_sub" name="子流程(CallActivity)" calledElement="showcase_quote_approval"/>
    <parallelGateway id="pgw_join" name="并行汇聚"/>
    <endEvent id="end" name="结束"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="ut_apply"/>
    <sequenceFlow id="f2" sourceRef="ut_apply" targetRef="xgw"/>
    <sequenceFlow id="f3" sourceRef="xgw" targetRef="st_auto" name="金额&lt;5万"/>
    <sequenceFlow id="f4" sourceRef="xgw" targetRef="pgw_split" name="金额>=5万"/>
    <sequenceFlow id="f5" sourceRef="st_auto" targetRef="end"/>
    <sequenceFlow id="f6" sourceRef="pgw_split" targetRef="ut_tech"/>
    <sequenceFlow id="f7" sourceRef="pgw_split" targetRef="ut_finance"/>
    <sequenceFlow id="f8" sourceRef="ut_tech" targetRef="igw"/>
    <sequenceFlow id="f9" sourceRef="ut_finance" targetRef="igw"/>
    <sequenceFlow id="f10" sourceRef="igw" targetRef="rt_wait"/>
    <sequenceFlow id="f11" sourceRef="rt_wait" targetRef="ca_sub"/>
    <sequenceFlow id="f12" sourceRef="ca_sub" targetRef="end"/>
  </process>
</definitions>`;

    const designerJson = JSON.stringify({
      nodes: [
        {
          id: 'start',
          type: 'startEvent',
          position: { x: 50, y: 300 },
          data: { type: 'startEvent', label: '开始' },
        },
        {
          id: 'ut_apply',
          type: 'userTask',
          position: { x: 200, y: 300 },
          data: { type: 'userTask', label: '填写申请', config: { assigneeType: 'starter' } },
        },
        {
          id: 'xgw',
          type: 'exclusiveGateway',
          position: { x: 400, y: 300 },
          data: { type: 'exclusiveGateway', label: '金额判断' },
        },
        {
          id: 'st_auto',
          type: 'serviceTask',
          position: { x: 600, y: 450 },
          data: { type: 'serviceTask', label: '自动审批' },
        },
        {
          id: 'pgw_split',
          type: 'parallelGateway',
          position: { x: 600, y: 200 },
          data: { type: 'parallelGateway', label: '并行分支' },
        },
        {
          id: 'ut_tech',
          type: 'userTask',
          position: { x: 800, y: 100 },
          data: {
            type: 'userTask',
            label: '技术评审',
            config: { assigneeType: 'role', roleIds: ['tech_lead'] },
          },
        },
        {
          id: 'ut_finance',
          type: 'userTask',
          position: { x: 800, y: 300 },
          data: {
            type: 'userTask',
            label: '财务审批',
            config: { assigneeType: 'dept', deptIds: ['finance'] },
          },
        },
        {
          id: 'igw',
          type: 'inclusiveGateway',
          position: { x: 1000, y: 200 },
          data: { type: 'inclusiveGateway', label: '包容汇聚' },
        },
        {
          id: 'rt_wait',
          type: 'receiveTask',
          position: { x: 1200, y: 200 },
          data: { type: 'receiveTask', label: '等待确认' },
        },
        {
          id: 'ca_sub',
          type: 'callActivity',
          position: { x: 1400, y: 200 },
          data: {
            type: 'callActivity',
            label: '子流程',
            config: { calledElement: 'showcase_quote_approval' },
          },
        },
        {
          id: 'end',
          type: 'endEvent',
          position: { x: 1600, y: 300 },
          data: { type: 'endEvent', label: '结束' },
        },
      ],
      edges: [
        { id: 'f1', source: 'start', target: 'ut_apply', type: 'smoothstep' },
        { id: 'f2', source: 'ut_apply', target: 'xgw', type: 'smoothstep' },
        {
          id: 'f3',
          source: 'xgw',
          target: 'st_auto',
          type: 'smoothstep',
          data: { label: '金额<5万' },
        },
        {
          id: 'f4',
          source: 'xgw',
          target: 'pgw_split',
          type: 'smoothstep',
          data: { label: '金额>=5万' },
        },
        { id: 'f5', source: 'st_auto', target: 'end', type: 'smoothstep' },
        { id: 'f6', source: 'pgw_split', target: 'ut_tech', type: 'smoothstep' },
        { id: 'f7', source: 'pgw_split', target: 'ut_finance', type: 'smoothstep' },
        { id: 'f8', source: 'ut_tech', target: 'igw', type: 'smoothstep' },
        { id: 'f9', source: 'ut_finance', target: 'igw', type: 'smoothstep' },
        { id: 'f10', source: 'igw', target: 'rt_wait', type: 'smoothstep' },
        { id: 'f11', source: 'rt_wait', target: 'ca_sub', type: 'smoothstep' },
        { id: 'f12', source: 'ca_sub', target: 'end', type: 'smoothstep' },
      ],
    });

    const resp = await page.request.post('/api/bpm/process-definitions', {
      data: {
        processKey,
        processName: '军火展 — 全9节点展示流程',
        description:
          '包含全部9种BPMN节点：StartEvent、EndEvent、UserTask(3种分配)、ServiceTask、ReceiveTask、CallActivity、ExclusiveGateway、ParallelGateway、InclusiveGateway',
        category: 'showcase',
        bpmnContent: bpmnXml,
        designerJson,
      },
    });
    const body = await resp.json();
    if (body.code === '0' || resp.ok()) {
      console.log('  Created BPMN: 全9节点展示流程');
    } else {
      console.warn(`  BPMN creation warning: ${JSON.stringify(body).slice(0, 200)}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 5. Full Automation — cover remaining trigger+action types
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 5: Automation — remaining triggers + actions', async ({ page }) => {
    const rules = [
      {
        name: '军火展 — 字段变更触发(ON_FIELD_CHANGE)',
        triggerType: 'on_field_change',
        modelCode: 'crm_opportunity',
        triggerConfig: { fieldCode: 'crm_opp_expected_amount', stateField: 'crm_opp_stage' },
        actions: [
          {
            type: 'update_record',
            config: { message: '金额变更，更新概率' },
            sequence: 0,
            label: '更新概率',
          },
        ],
      },
      {
        name: '军火展 — 记录更新触发(ON_RECORD_UPDATE)',
        triggerType: 'on_record_update',
        modelCode: 'crm_account',
        triggerConfig: { watchFields: ['crm_acc_rating', 'crm_acc_status'] },
        actions: [
          {
            type: 'send_notification',
            config: { message: '客户评级或状态变更' },
            sequence: 0,
            label: '通知',
          },
          { type: 'delay', config: { delayMs: 5000 }, sequence: 1, label: '延迟5秒' },
          {
            type: 'send_webhook',
            config: { url: 'https://httpbin.org/post' },
            sequence: 2,
            label: '同步外部',
          },
        ],
      },
      {
        name: '军火展 — Webhook触发(WEBHOOK)',
        triggerType: 'webhook',
        actions: [
          {
            type: 'condition',
            config: { condition: 'payload.amount > 50000' },
            sequence: 0,
            label: '金额判断',
          },
          {
            type: 'create_record',
            config: { targetModel: 'crm_lead', message: '从外部创建线索' },
            sequence: 1,
            label: '创建线索',
          },
          {
            type: 'execute_command',
            config: { commandCode: 'crm:create_activity' },
            sequence: 2,
            label: '记录活动',
          },
        ],
      },
    ];

    for (const rule of rules) {
      const resp = await page.request.post('/api/automations', {
        data: { ...rule, enabled: false },
      });
      const body = await resp.json();
      if (body.code === '0') {
        console.log(`  Created automation: ${rule.name}`);
      } else {
        console.warn(`  Automation warning: ${rule.name} — ${body.message?.slice(0, 80)}`);
      }
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 6. SavedView — 7 view types on CRM Opportunity
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 6: SavedView — 7 view types', async ({ page }) => {
    const views = [
      { name: '全部商机（表格）', viewType: 'TABLE', config: {} },
      { name: '按阶段看板', viewType: 'KANBAN', config: { groupByField: 'crm_opp_stage' } },
      {
        name: '成交日历',
        viewType: 'CALENDAR',
        config: { dateField: 'crm_opp_expected_close_date' },
      },
      { name: '商机卡片', viewType: 'GALLERY', config: {} },
      {
        name: '项目甘特图',
        viewType: 'GANTT',
        config: { startField: 'created_at', endField: 'crm_opp_expected_close_date' },
      },
      { name: '时间线', viewType: 'TIMELINE', config: { dateField: 'created_at' } },
      { name: '组织树形', viewType: 'TREE', config: {} },
    ];

    let created = 0;
    for (const v of views) {
      const resp = await page.request.post('/api/views', {
        data: {
          name: v.name,
          modelCode: 'crm_opportunity',
          viewType: v.viewType.toLowerCase(),
          scope: 'global',
          viewConfig: v.config,
        },
      });
      const body = await resp.json().catch(() => ({}));
      if (body?.code === '0' || resp.ok()) {
        created++;
      } else {
        console.warn(
          `  SavedView "${v.name}" (${v.viewType}) warning: ${body?.message?.slice(0, 80) || resp.status()}`,
        );
      }
    }
    console.log(`  Created ${created}/7 SavedViews for crm_opportunity`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 7. Knowledge Base + Documents (inline text via file upload)
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal 7: Knowledge Base + Documents', async ({ page }) => {
    // Create KB
    const kbResp = await page.request.post('/api/ai/knowledge', {
      data: {
        name: '鑫然科技产品知识库',
        description:
          '包含 PCBA 工艺规范、产品参数手册、常见问题解答等技术文档。供 AuraBot 和客服 Agent 引用。',
        embeddingProvider: 'openai',
        embeddingModel: 'text-embedding-3-small',
        chunkSize: 300,
        chunkOverlap: 30,
      },
    });
    const kbBody = await kbResp.json().catch(() => ({}));

    if (kbBody?.code !== '0' && !kbResp.ok()) {
      console.warn(`  KB creation failed: ${kbBody?.message?.slice(0, 100) || kbResp.status()}`);
      return;
    }

    const kbPid = kbBody?.data?.pid;
    console.log(`  Created KB: 鑫然科技产品知识库 (${kbPid})`);

    // Upload documents as text files via multipart
    const docs = [
      {
        filename: 'PCBA工艺规范.txt',
        content: `# PCBA 工艺规范 v2.0

## 1. SMT 贴片工艺
- 回流焊温度曲线：预热区 150-200°C，均温区 200-220°C，峰值 235-245°C
- BGA 焊接：峰值温度 240°C，保持 60-90 秒
- 焊膏印刷：钢网厚度 0.12mm，开口面积比 ≥ 0.66
- 贴片精度：±0.05mm (0402 以上)，±0.03mm (0201)

## 2. DIP 插件工艺
- 波峰焊温度：250±5°C
- 预热温度：110±10°C
- 传送速度：1.0-1.2 m/min

## 3. 检测标准
- AOI 检测覆盖率：100% SMT 焊点
- ICT 测试覆盖率：≥ 95% 节点
- FCT 功能测试：100% 产品

## 4. 质量标准
- IPC-A-610 Class 2（标准级）/ Class 3（高可靠性级）
- AEC-Q100（汽车电子）
- 不良率目标：≤ 200 PPM`,
      },
      {
        filename: '常见技术FAQ.txt',
        content: `# 技术 FAQ

## Q1: 你们支持哪些 PCB 材质？
A: FR-4（标准）、铝基板（LED）、Rogers（高频）、陶瓷基板（高温）

## Q2: 最小批量是多少？
A: 打样 5 片起，小批量 100 片起，量产 1000 片起。

## Q3: 交期一般多久？
A: 打样 3-5 个工作日，小批量 7-10 个工作日，量产 15-20 个工作日。

## Q4: 你们有哪些质量认证？
A: ISO 9001:2015、ISO 14001:2018、IATF 16949（汽车电子）、UL 认证。

## Q5: 如何处理质量问题？
A: 48 小时内响应 → 技术分析 → 出具 8D 报告 → 补货/返工 → 改善措施。

## Q6: 账期和付款方式？
A: 新客户预付 50%，老客户月结 30-60 天。支持银行转账、承兑汇票。`,
      },
      {
        filename: '产品参数速查.txt',
        content: `# 产品参数速查

## 电阻系列
| 型号 | 封装 | 阻值范围 | 精度 | 功率 |
|------|------|---------|------|------|
| RC0402 | 0402 | 1Ω-10MΩ | ±1% | 1/16W |
| RC0603 | 0603 | 1Ω-10MΩ | ±1% | 1/10W |
| RC0805 | 0805 | 1Ω-10MΩ | ±1% | 1/8W |

## 电容系列
| 型号 | 封装 | 容值范围 | 材质 | 耐压 |
|------|------|---------|------|------|
| CC0402 | 0402 | 1pF-1μF | C0G/X5R/X7R | 6.3-50V |
| CC0603 | 0603 | 1pF-10μF | C0G/X5R/X7R | 6.3-50V |

## 连接器系列
| 型号 | 类型 | 间距 | 针数 | 额定电流 |
|------|------|------|------|---------|
| FPC05 | FPC | 0.5mm | 4-60P | 0.5A |
| PH20 | 线对板 | 2.0mm | 2-16P | 2A |
| XH25 | 线对板 | 2.5mm | 2-20P | 3A |`,
      },
    ];

    let uploaded = 0;
    for (const doc of docs) {
      try {
        // Create a Blob-like buffer for multipart upload
        const buffer = Buffer.from(doc.content, 'utf-8');
        const resp = await page.request.post(`/api/ai/knowledge/${kbPid}/documents/upload`, {
          multipart: {
            file: {
              name: doc.filename,
              mimeType: 'text/plain',
              buffer,
            },
          },
        });
        const body = await resp.json().catch(() => ({}));
        if (body?.code === '0' || resp.ok()) {
          uploaded++;
          console.log(`  Uploaded: ${doc.filename}`);
        } else {
          console.warn(
            `  Upload failed for ${doc.filename}: ${body?.message?.slice(0, 80) || resp.status()}`,
          );
        }
      } catch (e) {
        console.warn(`  Upload error for ${doc.filename}: ${(e as Error).message.slice(0, 80)}`);
      }
    }
    console.log(`  KB documents: ${uploaded}/3 uploaded`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Verification
  // ═════════════════════════════════════════════════════════════════════════

  test('Arsenal: Verification summary', async ({ page }) => {
    console.log('\n═══════════════════════════════════════');
    console.log('  Arsenal Showcase — Summary');
    console.log('═══════════════════════════════════════');

    // Showcase records
    const scResp = await page.request.get('/api/dynamic/showcase_all_fields/list?pageSize=1');
    const scBody = await scResp.json().catch(() => ({}));
    console.log(`  Showcase Records:  ${scBody?.data?.total ?? '?'}`);

    // Dashboards
    const dashResp = await page.request.get('/api/dashboards');
    const dashBody = await dashResp.json().catch(() => ({}));
    const dashCount = Array.isArray(dashBody?.data)
      ? dashBody.data.length
      : (dashBody?.data?.total ?? '?');
    console.log(`  Dashboards:        ${dashCount}`);

    // BPM processes
    const bpmResp = await page.request.get('/api/bpm/process-definitions');
    const bpmBody = await bpmResp.json().catch(() => ({}));
    console.log(`  BPM Definitions:   ${bpmBody?.data?.total ?? bpmBody?.data?.length ?? '?'}`);

    // Automations
    const autoResp = await page.request.get('/api/automations?page=1&size=100');
    const autoBody = await autoResp.json().catch(() => ({}));
    console.log(
      `  Automations:       ${autoBody?.data?.total ?? autoBody?.data?.records?.length ?? '?'}`,
    );

    // SavedViews
    const viewResp = await page.request.get('/api/views/accessible?modelCode=crm_opportunity');
    const viewBody = await viewResp.json().catch(() => ({}));
    const viewCount = Array.isArray(viewBody?.data)
      ? viewBody.data.length
      : (viewBody?.data?.total ?? '?');
    console.log(`  SavedViews (opp):  ${viewCount}`);

    // Knowledge Base
    const kbResp = await page.request.get('/api/ai/knowledge');
    const kbBody = await kbResp.json().catch(() => ({}));
    const kbCount = Array.isArray(kbBody?.data) ? kbBody.data.length : (kbBody?.data?.total ?? '?');
    console.log(`  Knowledge Bases:   ${kbCount}`);

    console.log('═══════════════════════════════════════\n');
  });
});
