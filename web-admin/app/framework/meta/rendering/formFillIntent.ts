/** A bounded, explicit draft command; quoted source text never authorizes a patch. */
export function isExplicitFormFillRequest(text: string): boolean {
  const directive = text
    .trim()
    .split(/[\r\n:：。！？!?]/, 1)[0]
    .trim()
    .replace(/[，,]\s*(?:请)?(?:先)?(?:不要|不必|无需)(?:自动)?(?:保存|提交)(?:记录|表单)?$/, '')
    .replace(/,\s*(?:do not|don't)\s+(?:save|submit)$/i, '');
  if (!directive || directive.length > 160 || /["'“”‘’「」『』]/.test(directive)) return false;
  if (
    /(不要|别|勿|无需|不必|不需要|禁止|先不|暂不|怎么|如何|能否|是否|解释|说明|示例|例子)/.test(
      directive,
    )
  )
    return false;
  if (/\b(don't|do not|never|how|explain|example|whether|could|can)\b/i.test(directive))
    return false;
  return (
    /^(?:请帮我|请|帮我|麻烦)?(?:(?:根据|按照|把|将)[^:：\r\n]{1,100})?(?:填写|填入|补全)(?:当前|这个|此)(?:页面的)?表单$/.test(
      directive,
    ) || /^(?:please\s+)?fill(?:\s+in)?\s+(?:the\s+)?(?:current|this)\s+form$/i.test(directive)
  );
}
