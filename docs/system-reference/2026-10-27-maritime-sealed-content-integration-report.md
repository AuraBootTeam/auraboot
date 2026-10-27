---
type: system-reference
status: active
created: 2026-10-27
---

# 主线公告内容证明整合验证

统一生产构件源为`0c299e033946e1228e0af700bb1abedaaf4ffe0f`；最终新增命令链测试源为`561cb786081b6b3b5be11cff1d78cede1f6008d5`。两者仅差一个测试文件，生产源码、API和迁移没有改变；本报告提交不改标构件producer。

## 分层结果

| 范围 | 实际证据 | 边界 |
| --- | --- | --- |
| HandlerPhase/封存/锁/权限缓存 | 87项host hermetic通过；4项未变API固定向量复用前轮结果 | 不是HTTP或业务消费者 |
| PostgreSQL与本地附件存储 | 0c源10项通过，0 fail/error/skip | 直接factory/事务；受控审批绑定 |
| 真实命令链 | 561源3项通过，0 fail/error/skip | 实际PF4J→registry→CommandExecutor→typed getter；受控审批绑定 |
| Schema生成与slot26升级 | 官方fresh生成器157条；新snapshot66行；临时库确认为0；原26仅应用新增1条并validate157 | 不复制其他分支snapshot，不新建产品数据库架构 |
| 独立统一21构件 | runtime/API/Core migration3项变更，18项保持；boot内API逐字节等于单一API；旧rich getters保留 | Root产品/消费者验收另计 |

三项真实命令链分别证明：已授权发布者并非收件人也可取得精确内容且提交marker；在handler拿到proof后通过正式typed角色接口撤销当前publisher关联，会在beforeCommit拒绝并回滚marker与角色关联；已提交撤权后保留独立invoke/read角色，handler仍到达而内容proof被拒绝。发布权只来自实际绑定release的角色权限与精确成员关联，没有用删除fallback permission行冒充runtime-primary撤权。

原失败保留：新IT首次误把generic PF4J实例当作受固定owner保护的privileged attestation对象，BeforeAll失败；修正为generic不获privileged证明，并保留started wrapper、classloader、canonical registry instance和真实JAR摘要正控。第二次3例中2例在setup复用了provider instance，命中真实unique约束；改为每个exact version的fixture instance后3例通过，未删约束或旧数据。更早host测试的import/内部错误oracle及真实ConflictException降级缺陷见[原始分层证明](../e2e/evidence/maritime-sealed-content-root-2026-10-27/root-qualification.json)。

当前固定candidate的boot SHA为`ac678d78f968748383ca8e8dd9d454c56ee71224934d15f469782101c60afff9`，API SHA为`684eff64dd358e67af1c1e8167a133f399c0fa247a71a15e6e0d2a0d6d51c9fc`，catalog SHA为`c0422e78104044ca2b8bb9ae9a7d02a6f1973ebfae2d052619de975fdeca2316`。新测试未重新发布不同生产包。

[PG证明](../e2e/evidence/maritime-sealed-content-root-2026-10-27/pg-proof.json)、[真实命令链证明](../e2e/evidence/maritime-sealed-content-root-2026-10-27/rich-proof.json)、[catalog证明](../e2e/evidence/maritime-sealed-content-root-2026-10-27/catalog-proof.json)记录精确producer及本地原始证据指纹。原XML/log保留在已登记`maritime-parallel-core/sealed-content-root`，不将其他窗口的component结果改标为Root执行。

仍未证明实际BPM审批、官方submit→worker→publicHTTP完整旅程、P不可变适用映射与签收、浏览器或全产品验收。外部对象最后复核后的改写不能由SQL锁或观测摘要排除；未建立任意并发IAM写入的全局线性撤权保证。生产VTS、设备联调、部署发布均不在上述测试结论中。
