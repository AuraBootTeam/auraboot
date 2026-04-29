/**
 * Showcase Demo Data — AI / ACP / Knowledge Base
 *
 * Creates:
 * - 3 Agent definitions (Sales Agent, Data Analyst, Support Agent)
 * - Knowledge base with sample documents
 *
 * Note: LLM Provider configuration requires actual API keys.
 * This script creates Agent definitions that will work once a provider is configured.
 *
 * Run AFTER seed-showcase-data.spec.ts:
 *   npx playwright test tests/api/setup/seed-showcase-ai.spec.ts
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

test.describe.serial('Showcase Seed — AI & ACP', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  test.setTimeout(120_000);

  // ═════════════════════════════════════════════════════════════════════════
  // Agent Definitions
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase AI1: ACP — Agent Definitions (3)', async ({ page }) => {
    // ACP uses field names: agent_code, name, description, agent_type, system_prompt, status
    // (NOT acp_ad_* prefix — different from DSL convention)
    const agents = [
      {
        agent_code: 'sales_agent',
        name: '销售助手 (Sales Agent)',
        agent_type: 'copilot',
        description:
          '辅助销售团队完成日常工作：查询客户信息、分析商机、生成报价建议、跟进提醒。支持自然语言交互，自动调用 CRM 数据查询工具。',
        status: 'active',
        system_prompt:
          '你是鑫然科技的销售助手。你的职责是帮助销售团队更高效地工作。你可以：1. 查询客户信息、联系人、商机状态 2. 分析销售漏斗和业绩数据 3. 提供跟进建议和报价参考 4. 生成客户拜访计划。回答时使用中文，语气专业但友好。',
      },
      {
        agent_code: 'data_analyst',
        name: '数据分析师 (Data Analyst)',
        agent_type: 'autonomous',
        description:
          '自动化数据分析任务：生成销售报表、分析客户趋势、预测收入、监控 KPI 异常。可定时执行或按需触发。',
        status: 'active',
        system_prompt:
          '你是鑫然科技的数据分析师 Agent。你的职责是分析业务数据并提供洞察。输出格式：先给结论，再给数据支撑，最后给建议。使用表格展示数据。',
      },
      {
        agent_code: 'support_agent',
        name: '客服支持 (Support Agent)',
        agent_type: 'reactive',
        description:
          '响应式客户服务 Agent：处理客户咨询、查询订单状态、解答产品技术问题、记录投诉和售后需求。基于知识库回答技术问题。',
        status: 'active',
        system_prompt:
          '你是鑫然科技的客户服务 Agent。你的职责是帮助客户解决问题。回答原则：先确认客户问题，再提供解决方案。技术问题引用知识库文档。无法解决时主动升级给人工。',
      },
    ];

    for (const agent of agents) {
      try {
        const id = await cmd(page, 'acp:create_agent_definition', agent);
        console.log(`  Created agent: ${agent.name}`);
      } catch (e) {
        console.warn(
          `  Agent creation failed for ${agent.name}: ${(e as Error).message.slice(0, 120)}`,
        );
      }
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Knowledge Base + Documents
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase AI2: Knowledge Base — Create KB and Documents', async ({ page }) => {
    // Create knowledge base
    const kbResp = await page.request.post('/api/ai/knowledge-base', {
      data: {
        name: '鑫然科技产品知识库',
        description:
          '包含 PCBA 工艺规范、产品参数手册、常见问题解答等技术文档。供 AuraBot 和客服 Agent 引用。',
      },
    });
    const kbBody = await kbResp.json().catch(() => ({}));

    if (kbBody?.code === '0') {
      const kbPid = kbBody.data?.pid || kbBody.data?.id;
      console.log(`  Created knowledge base: 鑫然科技产品知识库 (${kbPid})`);

      // Add inline text documents (no file upload needed)
      const docs = [
        {
          title: 'PCBA 工艺规范 v2.0',
          content: `# PCBA 工艺规范

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
          title: '常见技术问题 FAQ',
          content: `# 技术 FAQ

## Q1: 你们支持哪些 PCB 材质？
A: FR-4（标准）、铝基板（LED）、Rogers（高频）、陶瓷基板（高温）

## Q2: 最小批量是多少？
A: 打样 5 片起，小批量 100 片起，量产 1000 片起。

## Q3: 交期一般多久？
A: 打样 3-5 个工作日，小批量 7-10 个工作日，量产 15-20 个工作日。加急可协商。

## Q4: 你们有哪些质量认证？
A: ISO 9001:2015、ISO 14001:2018、IATF 16949（汽车电子）、UL 认证。

## Q5: 如何处理质量问题？
A: 48 小时内响应 → 技术分析 → 出具 8D 报告 → 补货/返工 → 改善措施。

## Q6: 账期和付款方式？
A: 新客户预付 50%，老客户月结 30-60 天。支持银行转账、承兑汇票。`,
        },
        {
          title: '产品参数速查手册',
          content: `# 产品参数速查

## 1. 电阻系列
| 型号 | 封装 | 阻值范围 | 精度 | 功率 |
|------|------|---------|------|------|
| RC0402 | 0402 | 1Ω-10MΩ | ±1% | 1/16W |
| RC0603 | 0603 | 1Ω-10MΩ | ±1% | 1/10W |
| RC0805 | 0805 | 1Ω-10MΩ | ±1% | 1/8W |

## 2. 电容系列
| 型号 | 封装 | 容值范围 | 材质 | 耐压 |
|------|------|---------|------|------|
| CC0402 | 0402 | 1pF-1μF | C0G/X5R/X7R | 6.3-50V |
| CC0603 | 0603 | 1pF-10μF | C0G/X5R/X7R | 6.3-50V |
| CC0805 | 0805 | 1pF-22μF | X5R/X7R | 6.3-50V |

## 3. 连接器系列
| 型号 | 类型 | 间距 | 针数 | 额定电流 |
|------|------|------|------|---------|
| FPC05 | FPC | 0.5mm | 4-60P | 0.5A |
| PH20 | 线对板 | 2.0mm | 2-16P | 2A |
| XH25 | 线对板 | 2.5mm | 2-20P | 3A |`,
        },
      ];

      for (const doc of docs) {
        // Try to create document with inline content
        const docResp = await page.request.post(`/api/ai/knowledge-base/${kbPid}/documents`, {
          data: {
            title: doc.title,
            content: doc.content,
            documentType: 'markdown',
          },
        });
        const docBody = await docResp.json().catch(() => ({}));
        if (docBody?.code === '0') {
          console.log(`  Created KB doc: ${doc.title}`);
        } else {
          console.warn(
            `  KB doc creation failed: ${doc.title} — ${docBody?.message?.slice(0, 80) || 'unknown'}`,
          );
        }
      }
    } else {
      console.warn(
        `  Knowledge base creation failed: ${kbBody?.message?.slice(0, 100) || 'unknown'}`,
      );
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Verification
  // ═════════════════════════════════════════════════════════════════════════

  test('Verification: AI seed summary', async ({ page }) => {
    console.log('\n═══════════════════════════════════════');
    console.log('  AI Seed — Summary');
    console.log('═══════════════════════════════════════');

    // Check agents
    const agentResp = await page.request.get('/api/dynamic/agent_definition/list?pageSize=1');
    const agentBody = await agentResp.json().catch(() => ({}));
    console.log(`  Agent Definitions: ${agentBody?.data?.total ?? '?'}`);

    // Check knowledge bases
    const kbResp = await page.request.get('/api/ai/knowledge-base');
    const kbBody = await kbResp.json().catch(() => ({}));
    const kbCount = Array.isArray(kbBody?.data) ? kbBody.data.length : (kbBody?.data?.total ?? '?');
    console.log(`  Knowledge Bases:   ${kbCount}`);

    console.log('═══════════════════════════════════════\n');
  });
});
