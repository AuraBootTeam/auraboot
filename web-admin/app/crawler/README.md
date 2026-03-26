# Crawler Frontend

爬虫管理前端界面。

## 功能

- ✅ 任务管理
  - 创建任务
  - 任务列表
  - 执行任务
- ✅ 文章查看
  - 文章列表
  - 筛选（来源、股票）
  - 内容展开

## 路由

- `/crawler/tasks` - 任务列表
- `/crawler/tasks/new` - 创建任务
- `/crawler/articles` - 文章列表

## 组件

- `TaskListPage` - 任务列表页
- `TaskCreatePage` - 创建任务页
- `ArticleListPage` - 文章列表页
- `store.ts` - Zustand 状态管理
