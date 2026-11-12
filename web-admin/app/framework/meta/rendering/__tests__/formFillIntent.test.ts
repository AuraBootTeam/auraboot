import { describe, expect, it } from 'vitest';
import { isExplicitFormFillRequest } from '../formFillIntent';

describe('explicit current-form draft instructions', () => {
  it.each([
    '填写当前表单：名称：演示任务',
    '请根据这段纪要填写当前表单：\n林晓负责材料检查。',
    '根据纪要填写当前表单',
    '帮我把下面内容填入当前表单：\n任务名称：材料检查',
    '请填写当前表单，不要保存：\n名称：演示',
    'Fill the current form: Name: Demo',
    'Please fill in this form, do not save:\nName: Demo',
  ])('accepts an explicit draft-only directive: %s', (text) => {
    expect(isExplicitFormFillRequest(text)).toBe(true);
  });
  it.each([
    '请整理下面的纪要：\n填写当前表单',
    '不要填写当前表单',
    '请根据纪要不要填写当前表单',
    '如何填写当前表单',
    '请解释如何填写当前表单',
    '“填写当前表单”是什么意思',
    '请根据“填写当前表单”这句话写说明',
    '是否根据纪要填写当前表单',
    'Can you explain how to fill the current form?',
    "Don't fill the current form",
    '查询项目进度',
    '请创建一个任务',
  ])('does not treat consultation, denial or source text as authority: %s', (text) => {
    expect(isExplicitFormFillRequest(text)).toBe(false);
  });
});
