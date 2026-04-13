# BPMN流程设计器

基于React Flow的BPMN 2.0流程设计器，支持可视化创建和编辑业务流程。

## 功能特性

### 1. 完整的BPMN节点支持

- **开始事件 (StartEvent)**: 流程的起始节点
- **结束事件 (EndEvent)**: 流程的结束节点
- **用户任务 (UserTask)**: 需要人工处理的任务节点
- **服务任务 (ServiceTask)**: 自动执行的服务节点
- **接收任务 (ReceiveTask)**: 等待接收消息的任务节点
- **排他网关 (ExclusiveGateway)**: 条件分支节点

### 2. 人员分配配置

用户任务支持多种人员分配方式:

- **指定用户**: 直接指定具体的用户ID
- **指定角色**: 根据角色分配任务
- **指定部门**: 根据部门分配任务
- **流程发起人**: 分配给发起流程的用户
- **表达式**: 使用表达式动态计算分配对象

审批模式:
- **单人审批**: 指定一个人审批
- **会签**: 需要所有人都审批通过
- **依次审批**: 按顺序依次审批

### 3. 可视化设计

- **拖拽式设计**: 从组件库拖拽节点到画布
- **连线编辑**: 通过拖拽创建节点之间的连接
- **属性配置**: 选中节点/连线后在右侧配置属性
- **实时预览**: 所见即所得的设计体验

### 4. 流程验证

- **结构验证**: 检查流程是否有开始/结束节点
- **连接验证**: 检查节点的输入输出连接是否正确
- **规则验证**: 检查节点配置是否符合BPMN规范
- **实时反馈**: 验证错误实时显示在界面上

### 5. 数据持久化

- **保存/加载**: 支持保存流程定义到数据库
- **版本管理**: 支持流程定义的版本控制
- **发布管理**: 支持草稿和发布状态
- **导入/导出**: 支持JSON格式的导入导出

## 使用方法

### 访问设计器

在浏览器中访问: `http://localhost:5173/bpmn-designer`

### 创建流程

1. 输入流程名称和流程标识(key)
2. 从左侧组件库拖拽节点到画布
3. 点击节点的连接点创建连线
4. 选中节点后在右侧配置属性
5. 点击"验证"按钮检查流程
6. 点击"保存"按钮保存流程

### 配置用户任务

1. 拖拽"用户任务"节点到画布
2. 选中节点
3. 在右侧属性面板配置:
   - 任务名称和描述
   - 人员分配类型
   - 具体的用户/角色/部门ID
   - 审批模式
   - 优先级
   - 是否可跳过

### 配置服务任务

1. 拖拽"服务任务"节点到画布
2. 选中节点
3. 配置服务类型:
   - **HTTP服务**: 配置服务URL
   - **Java类**: 配置类名
   - **脚本**: 配置脚本类型和内容
4. 设置是否异步执行

### 配置网关

1. 拖拽"排他网关"到画布
2. 从网关创建多条输出连线
3. 选中连线配置条件表达式
4. 可选设置默认流向

## 技术架构

### 核心技术栈

- **React Flow**: 流程图可视化库
- **Zustand**: 状态管理
- **TypeScript**: 类型安全
- **Tailwind CSS**: 样式框架

### 目录结构

```
app/bpmn-designer/
├── components/          # 组件
│   ├── nodes/          # BPMN节点组件
│   │   ├── StartEventNode.tsx
│   │   ├── EndEventNode.tsx
│   │   ├── UserTaskNode.tsx
│   │   ├── ServiceTaskNode.tsx
│   │   ├── ReceiveTaskNode.tsx
│   │   └── ExclusiveGatewayNode.tsx
│   ├── BPMNCanvas.tsx      # 画布组件
│   ├── BPMNPalette.tsx     # 组件库
│   └── BPMNPropertyPanel.tsx # 属性面板
├── store/              # 状态管理
│   └── useBPMNStore.ts
├── services/           # API服务
│   └── bpmnService.ts
├── types/              # 类型定义
│   └── index.ts
├── constants/          # 常量配置
│   └── index.ts
├── BPMNDesigner.tsx    # 主组件
└── README.md
```

### 数据模型

#### BPMNProcessDefinition

```typescript
interface BPMNProcessDefinition {
  id?: string;
  name: string;              // 流程名称
  key: string;               // 流程标识
  description?: string;      // 描述
  category?: string;         // 分类
  version?: number;          // 版本号
  nodes: BPMNNode[];         // 节点列表
  edges: BPMNEdge[];         // 连线列表
  variables?: Record<string, any>; // 流程变量
  status?: 'draft' | 'published' | 'suspended';
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
}
```

#### BPMNNode

```typescript
interface BPMNNode {
  id: string;
  type: BPMNNodeType;        // 节点类型
  position: { x: number; y: number };
  data: {
    type: BPMNNodeType;
    label: string;           // 显示标签
    config?: NodeConfig;     // 节点配置
  };
}
```

#### 用户任务配置

```typescript
interface UserTaskConfig {
  name: string;
  description?: string;
  assignee?: AssigneeConfig;  // 人员分配
  candidateUsers?: string[];  // 候选用户
  candidateGroups?: string[]; // 候选组
  formKey?: string;           // 关联表单
  priority?: number;          // 优先级
  skipable?: boolean;         // 是否可跳过
}

interface AssigneeConfig {
  type: 'user' | 'role' | 'dept' | 'starter' | 'expression';
  userIds?: string[];
  roleIds?: string[];
  deptIds?: string[];
  expression?: string;
  assigneeMode?: 'single' | 'multi' | 'sequential';
}
```

## API接口

### 保存流程定义

```
POST /api/bpmn/processes
```

请求体:
```json
{
  "name": "请假流程",
  "key": "leave_process",
  "description": "员工请假审批流程",
  "nodes": [...],
  "edges": [...],
  "status": "draft"
}
```

### 更新流程定义

```
PUT /api/bpmn/processes/:id
```

### 获取流程定义

```
GET /api/bpmn/processes/:id
```

### 获取流程列表

```
GET /api/bpmn/processes?page=0&size=20&status=draft
```

### 发布流程

```
POST /api/bpmn/processes/:id/publish
```

### 导出XML

```
GET /api/bpmn/processes/:id/export
```

## 开发指南

### 添加新的节点类型

1. 在 `types/index.ts` 中定义节点类型和配置接口
2. 在 `constants/index.ts` 中添加节点样式和默认配置
3. 创建节点组件 `components/nodes/YourNodeType.tsx`
4. 在 `components/BPMNCanvas.tsx` 中注册节点类型
5. 在 `components/BPMNPalette.tsx` 中添加到组件库
6. 在 `components/BPMNPropertyPanel.tsx` 中添加属性配置

### 自定义节点样式

在 `constants/index.ts` 的 `BPMN_NODE_STYLES` 中配置:

```typescript
export const BPMN_NODE_STYLES = {
  [BPMNNodeType.YOUR_NODE]: {
    width: 120,
    height: 80,
    borderRadius: 8,
    borderWidth: 2,
    borderColor: '#3b82f6',
    backgroundColor: '#eff6ff',
  },
};
```

### 扩展验证规则

在 `store/useBPMNStore.ts` 的 `validate` 方法中添加自定义验证逻辑。

## 后续规划

- [ ] 支持更多BPMN节点类型(并行网关、包容网关等)
- [ ] 支持子流程
- [ ] 支持边界事件
- [ ] 支持BPMN 2.0 XML导入/导出
- [ ] 支持流程版本对比
- [ ] 支持流程模拟运行
- [ ] 集成流程引擎(Camunda/Flowable)
- [ ] 支持流程监控和统计

## 常见问题

### 1. 如何指定多个审批人?

在用户任务的人员分配中:
- 选择"指定用户"
- 在用户ID输入框中输入多个ID，用逗号分隔
- 设置审批模式为"会签"或"依次审批"

### 2. 如何设置条件分支?

1. 添加排他网关
2. 从网关创建多条输出连线
3. 选中每条连线
4. 在条件表达式中输入表达式，例如: `\${amount > 1000}`

### 3. 如何保存流程到数据库?

点击顶部工具栏的"保存"按钮，流程会通过BFF服务保存到后端数据库。

注意: 当前BFF服务中的数据库保存逻辑需要配合后端Spring Boot服务实现。

## 许可证

MIT
