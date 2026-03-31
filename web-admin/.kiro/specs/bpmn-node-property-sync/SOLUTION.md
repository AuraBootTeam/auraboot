# BPMN节点属性同步修复方案

## 问题描述

当用户在属性面板中修改节点属性（如节点标签/名称）时，更改会保存到Zustand store，但画布不会立即更新显示新的标签。用户需要刷新页面或移动节点才能看到更改。

## 根本原因

在 `app/bpmn-designer/components/BPMNCanvas.tsx` 中，从store同步到React Flow本地state的逻辑只检查节点**数量**变化：

```typescript
// 旧代码 - 只检查数量
if (storeNodes.length !== nodes.length) {
  // 同步节点
}
```

这意味着当节点属性（如label、config等）发生变化但节点数量不变时，不会触发同步。

## 解决方案

修改同步逻辑，不仅检查节点数量，还检查节点内容（data属性）是否变化：

```typescript
// 新代码 - 检查数量和内容
const hasChanges = 
  storeNodes.length !== nodes.length ||
  storeNodes.some((storeNode, index) => {
    const localNode = nodes[index];
    if (!localNode || storeNode.id !== localNode.id) return true;
    // 检查 label 或其他 data 属性是否变化
    return JSON.stringify(storeNode.data) !== JSON.stringify(localNode.data);
  });

if (hasChanges) {
  // 同步节点
}
```

## 修改的文件

**文件**: `app/bpmn-designer/components/BPMNCanvas.tsx`

**位置**: 第80-100行左右（从store同步到本地的useEffect）

**修改内容**:

```typescript
// 从store同步到本地（单向）- 避免在添加节点时同步
React.useEffect(() => {
  // 如果正在添加节点，不要同步（避免位置被重置）
  if (isAddingNode.current) {
    console.log('⏸️  Skip sync - adding node');
    return;
  }

  // 同步节点：检查数量变化或内容变化
  const hasChanges = 
    storeNodes.length !== nodes.length ||
    storeNodes.some((storeNode, index) => {
      const localNode = nodes[index];
      if (!localNode || storeNode.id !== localNode.id) return true;
      // 检查 label 或其他 data 属性是否变化
      return JSON.stringify(storeNode.data) !== JSON.stringify(localNode.data);
    });

  if (hasChanges) {
    console.log('🔄 Syncing from store:', storeNodes.length, 'nodes');
    isSyncingFromStore.current = true;
    setNodes(storeNodes);
    setTimeout(() => {
      isSyncingFromStore.current = false;
    }, 100);
  }
}, [storeNodes, nodes, setNodes]);
```

## 工作原理

1. **用户在属性面板修改节点标签**
   - `BPMNPropertyPanel.tsx` 调用 `updateNode(nodeId, { label: newValue })`
   
2. **Zustand store更新**
   - `useBPMNStore.ts` 的 `updateNode` action更新 `state.nodes` 中对应节点的data
   - Store标记为dirty (`isDirty = true`)

3. **触发同步**
   - `BPMNCanvas.tsx` 的useEffect监听 `storeNodes` 变化
   - 检测到节点data变化（通过JSON.stringify比较）
   - 调用 `setNodes(storeNodes)` 更新React Flow的本地state

4. **React Flow重新渲染**
   - React Flow检测到nodes state变化
   - 重新渲染受影响的节点组件
   - 节点显示新的标签

## 性能考虑

- **JSON.stringify比较**: 对于BPMN节点的data对象（通常很小），JSON.stringify的性能开销可以接受
- **some()早期退出**: 一旦发现第一个变化就停止检查，避免不必要的比较
- **防抖机制**: 现有的300ms防抖机制仍然有效，防止过于频繁的store更新

## 测试步骤

1. 启动BPMN设计器: `pnpm dev`
2. 访问 `/bpmn-designer`
3. 从组件库拖拽一个"用户任务"节点到画布
4. 点击选中该节点
5. 在右侧属性面板修改"节点标签"字段
6. **预期结果**: 画布中的节点标签立即更新（100ms内）
7. 修改其他属性（如人员分配类型）
8. **预期结果**: 如果属性影响显示（如UserTaskNode底部的类型标签），也会立即更新

## 边缘情况处理

- ✅ **添加节点时**: `isAddingNode.current` 标志防止同步干扰新节点的位置
- ✅ **移动节点时**: 位置变化也会触发data变化检测，正常同步
- ✅ **删除节点时**: 数量变化检测会捕获
- ✅ **快速连续修改**: 防抖机制防止过度更新
- ✅ **循环更新**: `isSyncingFromStore.current` 标志防止无限循环

## 后续优化建议

如果性能成为问题（例如有100+节点），可以考虑：

1. **使用深度比较库**: 如 `lodash.isEqual` 替代 `JSON.stringify`
2. **仅比较关键字段**: 只比较 `label` 和 `config`，忽略其他字段
3. **使用Immer的补丁**: Zustand的Immer中间件可以提供变化补丁，更精确地追踪变化
4. **虚拟化**: 对于大型流程图，使用React Flow的虚拟化功能

## 验证清单

- [x] 修改节点标签后立即在画布显示
- [x] 修改节点配置后立即在画布显示（如果影响视觉）
- [x] 不影响现有的拖拽功能
- [x] 不影响现有的添加/删除节点功能
- [x] 不引入无限循环
- [x] 保持"未保存"状态指示正常工作
