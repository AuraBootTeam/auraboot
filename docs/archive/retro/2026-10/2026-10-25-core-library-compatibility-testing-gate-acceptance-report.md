---
type: retro
status: closed
created: 2026-10-25
distilled_to:
  - ../../../../platform/gradle/README.md
---

# Core #2371 模块兼容与合并验收

allowed_claim：本批最小模块兼容修复通过有界的本地构建、产出、消费和原套集复验；不代表重新取得完整发布资格。browser E2E did_not_run（executed=0），本批没有 UI 改动。

## 范围与稳定契约

以 main `66f49f0f3ef3e5dd32bb0e8afc8e3b55ff8ff4b3` 的 Core/Agent/App/BOM 拆分为基线，继续原 #2371 实现线。
版本选择保留默认 VERSION、显式 auraPlatformVersion 及独立 LIBRARY_VERSION；混用拒绝。
默认 Core-only 发布闭包和 schema 1 保留，显式 App/BOM roots 使用 schema 2；BOM 保留原 POM/Module，不制造 JAR。更新 rc.3，旧候选不覆盖。
继续使用 main 的 stageCompatibilityBootJar，没有新建打包链或状态机。
测试入口按模块归属迁移；Bootstrap 专项明确选择原 App 用例，缺用例即失败，JUnit 收集覆盖各模块。
Workspace 配套只修改原 Maven 暂存器及原测试，校验显式根、平台约束和字节，区分默认库与测试附件 capability。

## 原套集和证据

证据根：`/Users/ghj/work/auraboot-evidence/workspace/2026-10-22-application-release-contract`。

| 被测面 | 实际执行与结果 | 证据 |
| --- | --- | --- |
| 构建与模块归属 | 所有模块 compileTestJava、旧 bootJar 兼容入口成功 | core2371-actual-build-v2.log |
| Gradle 原生版本/发布合同 | 25 场景通过；含默认、预览、库版本、非法组合、显式 roots、篡改与重复输出反例 | core2371-version-v2/result.json |
| Core 原 Node 套集 | 61 通过，0 失败/跳过 | core2371-merge-node-v4.log |
| Workspace 原 Maven 套集扩展 | 18 通过，0 失败/跳过；假 BOM JAR、缺失 BOM、类型/根/约束/生产依赖漂移仍拒绝 | core2371-staging-v4.log |
| 原 Java 定向复验 | 最终保存 JUnit：Core 70、Agent 7、App 53、Bootstrap 1，合计 131 通过，0 失败/跳过 | core2371-final-junit.json 及 core2371-final-junit/ |
| App 测试隔离/调度等 | 原 Java v2 中 3 类独立断言通过；同轮 2 类 DB 用例因缺省库缺失失败，纠正为本任务库后原样通过 | core2371-original-java-v2.log、core2371-original-app-v3.log |
| 原生库候选 | 隔离目录实际生成 9 个 JAR 模块和 1 个 BOM，37 个原始文件；无远端发布 | core2371-actual-candidate-v3/library-publication-inventory.json |
| 实际消费 | 原 agent-artifact-consumer 六种模式，Gradle module 与 Maven POM 两种解析均通过；只用隔离库，拒绝 producer project 与其他版本 | core2371-original-consumer-module-v1.log、core2371-original-consumer-pom-v1.log |
| Bootstrap 真实 DB | 新建专用 aura_boot_bootstrap_core2371，经原 Flyway 迁移和校验 143 条，原单用例真实执行通过 | core2371-bootstrap-migrate-v1.log、core2371-original-bootstrap-v1.log |

## 失败与复用边界

保留首轮重复测试字段导致的编译失败、缺离线依赖和缺省 aura_boot 连接失败；未将这些失败拼接为同轮通过。
后续仅重跑受影响检查：重复字段去重，跨模块 helper/原测试移位，依赖按原锁文件补齐，App 集成测试绑定既有 application-release-contract/auraboot_202。
Procurement 原字节保护断言仅修源码定位，原摘要和断言保留。真实库暂存首次揭示 fixture capability 与生产图混淆，修复后原始候选字节重验通过，无重编兜底。
旧 native107 的 16060/132 只属于旧源码，不继承为当前完整质量资格；未重投 Jenkins、夜间任务、完整产品发布门禁或远端 Maven 发布。

## Final Evidence Pack

- acceptance_report: 本文
- claim_level: targeted-tested
- current_sot: platform/gradle/README.md、main 原 published-agent consumer、Workspace tools/jenkins/README.md
- business_scope: 版本、发布闭包、JAR/BOM、模块测试与收据兼容
- integration_tests: 原 App provider 与专用 Bootstrap 真实 PostgreSQL 用例
- integration_coverage: coverage_not_measured；原 root JaCoCo 输出保留，不据此声明全仓覆盖率
- e2e_specs / e2e_collection / e2e_execution: did_not_run，browser executed=0
- feature_action_matrix: 本文范围表；无 UI 行动点
- browser_evidence / permission_negative / visual_feedback: 不适用 UI；权限相关原 Java 断言保留
- backend_evidence: 原生 JUnit 与原 runner 日志
- artifact_evidence: 候选清册、暂存清册、原六模式双解析消费者
- skip_fixme_threshold_retry_audit: 本批通过范围 0 skip；无断言/门槛放宽，无自动重试
- did_not_run: 远端库发布、完整发布门、生产、快照切换、浏览器
- remaining_blockers: 本批范围以最终 PR/head 和 main 收口证明为准；原整体发布管线的后续工作继续暂停
- allowed_claim: 本批有界兼容验收，不能称为新的完整产品发布验收
