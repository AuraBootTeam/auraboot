type Localize = (zh: string, en: string) => string;
type Labels = Record<string, { 'zh-CN': string; 'en-US': string }>;
const memberStatuses: Labels = {
  active: { 'zh-CN': '已激活', 'en-US': 'Active' },
  pending: { 'zh-CN': '待审批', 'en-US': 'Pending' },
  suspended: { 'zh-CN': '已暂停', 'en-US': 'Suspended' },
  rejected: { 'zh-CN': '已拒绝', 'en-US': 'Rejected' },
  inactive: { 'zh-CN': '已离开', 'en-US': 'Inactive' },
};
const authorizationMethods: Labels = {
  offline: { 'zh-CN': '线下授权', 'en-US': 'Offline authorization' },
  phone: { 'zh-CN': '电话授权', 'en-US': 'Phone authorization' },
  wechat: { 'zh-CN': '微信授权', 'en-US': 'WeChat authorization' },
  email: { 'zh-CN': '邮件授权', 'en-US': 'Email authorization' },
  other: { 'zh-CN': '其他', 'en-US': 'Other' },
};
export const authorizationMethodCodes = Object.keys(authorizationMethods);
function label(labels: Labels, value: string, localize: Localize): string {
  const text = labels[value.toLowerCase()];
  return text ? localize(text['zh-CN'], text['en-US']) : localize('未知', 'Unknown');
}
export const memberStatusLabel = (value: string, localize: Localize) => label(memberStatuses, value, localize);
export const authorizationMethodLabel = (value: string, localize: Localize) => label(authorizationMethods, value, localize);
