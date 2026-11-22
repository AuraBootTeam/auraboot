import { useI18n } from '~/contexts/I18nContext';

/** Shared capability copy for the default login and configurable auth templates. */
export const LOGIN_CAPABILITIES = [
  { key: 'build', zh: '搭得快，改得动', en: 'Build faster, adapt easily', zhDesc: 'AI、Skills 与 DSL 协同搭建，现成组件复用，设计器随时微调。', enDesc: 'Build with AI, Skills and DSL. Reuse components and refine pages in the designer.', icon: 'M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z' },
  { key: 'workflow', zh: '有规则，有闭环', en: 'Set rules, follow through', zhDesc: '规则引擎、审批、SLA 与自动化协同，串起业务处理全过程。', enDesc: 'Connect rules, approvals, SLA tracking and automation from submission to completion.', icon: 'M4 4h6v6H4z M14 14h6v6h-6z M14 7h3v7 M10 17H7v-7' },
  { key: 'insight', zh: '看全局，查明细', en: 'See the big picture, explore the details', zhDesc: '看板掌握进展，报告梳理结果，关键数据追到原始单据。', enDesc: 'Track progress in dashboards, review reports and trace key figures to source records.', icon: 'M4 3v17h17 M8 15v-4 M13 15V7 M18 15v-6' },
  { key: 'agent', zh: '懂业务，能办事', en: 'Understand the work, get it done', zhDesc: 'Agent 连接知识、工具和业务命令，在权限与确认机制下执行任务。', enDesc: 'Connect knowledge, tools and business commands. Execute tasks with permissions and confirmation.', icon: 'M12 3l2.5 5.5L20 11l-5.5 2.5L12 19l-2.5-5.5L4 11l5.5-2.5z' },
  { key: 'extend', zh: '可复用，能扩展', en: 'Reuse the essentials, extend what matters', zhDesc: '从按钮、组件到页面和业务命令，按需定制，通过插件与 API 连接系统。', enDesc: 'Customize buttons, components, pages and commands. Connect systems through plugins and APIs.', icon: 'M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 17h6 M17 14v6' },
  { key: 'security', zh: '分权限，可追溯', en: 'Control access, trace actions', zhDesc: '多租户与细粒度权限管理访问范围，审计与 Trace 记录执行过程。', enDesc: 'Scope access by tenant and permission. Review execution with audit records and traces.', icon: 'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M8 12l3 3 5-6' },
] as const;

export default function LoginCapabilities() {
  const { t, locale } = useI18n();
  const zh = locale.startsWith('zh');
  return <ul className="auth-capabilities" data-testid="login-capabilities">
    {LOGIN_CAPABILITIES.map(item => <li className="auth-capability" key={item.key}>
      <svg className="auth-capability-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d={item.icon} /></svg>
      <div><h2>{t(`auth.capability.${item.key}`, undefined, zh ? item.zh : item.en)}</h2><p>{t(`auth.capability.${item.key}.desc`, undefined, zh ? item.zhDesc : item.enDesc)}</p></div>
    </li>)}
  </ul>;
}
