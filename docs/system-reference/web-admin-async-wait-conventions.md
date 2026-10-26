---
type: system-reference
status: active
---

# web-admin 单测异步等待审计(waitFor 目标铁律)

## 约定(铁律)

`waitFor` 必须等待 **DOM 结果**,不得等待 **mock 被调用**:

```ts
// ❌ 抖动源:mock 被观察到调用时,组件 await 完 promise、setPreflight 提交渲染还没发生
await waitFor(() => expect(preflightFieldChange).toHaveBeenLastCalledWith({...}));
expect(screen.getByTestId('field-preflight-result')).toHaveTextContent('已阻断'); // getBy 可扑空

// ✅ 等渲染结果;mock 断言放在其后(此时必然已发生)
await waitFor(() =>
  expect(screen.getByTestId('field-preflight-result')).toHaveTextContent('已阻断'),
);
expect(preflightFieldChange).toHaveBeenLastCalledWith({...});
```

已修实例(全部同一根因,CI 负载下间歇红,污染无关依赖 PR):#2385(DetailPageContent flowRefresh)、#2386 同族、#2427(DecisionFieldImpactPanel 两处)、10-23 ExecutionLogTraceBlock(由 #2385 顺带修复)、2026-10-26 EventPolicyDesigner 两处(Workflow 的 `epd-run-result` 等 mock 后裸读 + Block 的字段选择器在策略数据到达前裸读,大合并拖慢 CI 触发双红)。判定为**同类不再新增修改**,新用例一律按 ✅ 写。

## 复扫脚本(2026-10-26 全仓扫描:796 文件,117 个启发式候选)

```bash
cd web-admin && python3 - <<'EOF'
import os, re, glob
hits = []
files = glob.glob(os.path.join('app', '**', '*.test.ts*'), recursive=True)
for f in files:
    src = open(f, encoding='utf-8').read()
    for m in re.finditer(r'await waitFor\(\(\) =>', src):
        i = src.index('(', m.start()); depth = 0; j = i
        while j < len(src):
            if src[j] == '(': depth += 1
            elif src[j] == ')':
                depth -= 1
                if depth == 0: break
            j += 1
        cb = src[i:j]
        end = src.index(');', j) + 2
        if not re.search(r'expect\(\s*screen\.', cb):
            tail = src[end:end+600]
            stop = re.search(r'\n\s*(await |fireEvent|it\(|test\(|\}\);)', tail)
            segment = tail[:stop.start()] if stop else tail
            dom = re.findall(r'screen\.(?:get|query)By[A-Za-z]+\(', segment)
            if dom:
                hits.append((f, src[:m.start()].count('\n') + 1, dom[:4]))
print(f"{len(files)} files, {len(hits)} candidates")
for h in hits: print("  %s:%d %s" % h)
EOF
```

## 甄别标准(先判真伪再动手,**禁止批量重写**)

启发式噪声大(2026-10-26 抽检 3/3 均非真风险),真阳性需同时满足:

1. waitFor 回调断言的对象是 **mock 对象**(vi.fn 实例),而非 screen 或已捕获的 DOM 元素引用——`expect(evidence).toHaveTextContent(...)`(evidence 来自 findByTestId)是正确写法,不是 mock;
2. 其后紧跟**抛错式读取**(`getBy*`),且目标节点**只在异步完成后渲染**(结果块/加载态替换/列表行);
3. 中间没有 `await screen.findBy*` 之类门卫。

常见假阳性:`queryBy*` 期望不存在(查询缺失不抛错);读同步渲染的表单字段(`getByLabelText`);被 `findBy*` 先行门卫兜住;读已由上一个 waitFor 稳定的同 commit 兄弟节点。

## 处置策略

- **按证据修,不按扫描修**:某用例在 CI 实际抖了 → 查本清单是否在列 → 按 ✅ 模式修 → 单文件连跑 5 次验证 → 修完在本文件登记。
- 每修一处,顺带检查同文件同模式是否成对出现(#2427 即一次修两处)。
- 修抖动的 PR 不夹带其他改动,标题惯例 `test(web): await ... before ...`(对齐 #2385/#2427)。
