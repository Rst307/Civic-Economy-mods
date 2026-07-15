# Civic Economy

Civic Economy 是面向 Minecraft 1.21.1 NeoForge 多国家服务器的经济与国家财政基础设施。

项目采用一套全服统一的 Lightman’s Currency 主货币；每个国家拥有独立国库，全服统一控制累计净发行量。国家系统负责国家身份与政治规则，Civic Economy 负责财政、支付、发行、领土维护和可审计经济指标。

## 当前状态

项目处于 **v0.1 可玩版持续实现阶段**。工程骨架、精确依赖探针、SQLite 财政事务、服务身份授权和建国申请/激活纵向切片已经落地；完整第一版仍在开发。实际完成范围、验证证据和剩余风险以 [`docs/IMPLEMENTATION_STATUS.md`](./docs/IMPLEMENTATION_STATUS.md) 为准。

## 必读文档

1. [v1.1 主规格](<./Civic Economy 经济核心 Mod 项目规格 v1.1.md>)
2. [领域术语表](./CONTEXT.md)
3. [架构决策](./docs/adr/)
4. [仓库开发规则](./AGENTS.md)

冲突时优先级为：主规格的已锁定规则 → ADR → `CONTEXT.md` 术语 → 实现代码。发现冲突时不要静默选择，应先更新决策或提出问题。

## 技术基线

- Java 21
- Minecraft 1.21.1
- NeoForge
- 强制集成：Lightman’s Currency、FTB Teams、FTB Chunks
- 可选集成：Create（机械动力）
- 财政持久化方向：SQLite WAL＋跨模组补偿事务

精确依赖版本必须通过技术探针验证，并由运行时白名单控制。不要仅凭版本范围假设兼容。

## 第一开发里程碑（已完成）

首个实现只应完成：

- NeoForge Gradle 工程骨架；
- 依赖加载与版本白名单；
- LC 自定义银行账户/玩家账户探针；
- FTB Teams 与 FTB Chunks 查询及事件探针；
- Create 可选加载和固定机器识别探针；
- 最小自动测试或 GameTest；
- 可在单人集成服务器运行的调试世界骨架与醒目隔离标记；
- 实际验证版本、运行命令和失败边界记录。

这一阶段不实现国库、铸币、领土扣费或生产评分。

后续开发已按用户授权继续推进完整 v1；上面的限制仅描述历史首个里程碑，不再限制当前分支。

## 单人测试

正式版本必须支持默认关闭、按世界隔离的单人调试模式。世界所有者可以在启用作弊的单人世界中显式开启，用一名真实玩家建立测试国家并测试资金、额度、周期推进、领土维护和故障恢复。调试世界必须永久标记，不能把测试资金或身份误当成正式服务器经济数据；专用服务器默认禁止启用。

## 开发流程

- `main` 保存可交接的稳定基线；
- 开发使用 `agent/<task>` 分支；
- 通过草稿 PR 交付，PR 必须说明验证命令和实际结果；
- 领域术语变化同步修改 `CONTEXT.md`；
- 难以逆转且存在真实权衡的决定才新增 ADR；
- 不得宣称只用 mock 完成的功能已经通过真实模组集成验证。

## 当前构建命令

需要 Java 21。Windows 使用仓库根目录中的 Gradle Wrapper：

```powershell
.\gradlew.bat clean build
.\gradlew.bat test
.\gradlew.bat resolveRuntimeDependencies
.\gradlew.bat runServer
.\gradlew.bat runServer -PincludeCreate=true
.\gradlew.bat runClient
.\gradlew.bat runGameTestServer
```

`runServer` 默认验证不安装 Create 的财政核心；传入 `-PincludeCreate=true` 才加载可选 Create 适配环境。真实验证结果与证据类型持续记录在 `docs/IMPLEMENTATION_STATUS.md`。

在受支持的 Lightman’s Currency `1.21-2.3.0.5` 运行时中，Civic Economy 会强制关闭 LC 原生 Coin Mint 铸造/熔化、实体与箱子免费货币掉落、村民/流浪商人货币交易、季节奖励和银行利息，并拒绝 `/give` LC 货币与 `/lcbank give`。该保护不改写管理员保存的 LC 配置文件，而是在运行时强制安全读值并在实际发行方法处再次拒绝；升级 LC 版本必须重新审查这些精确边界并重跑 GameTest。

## 当前游戏内命令

玩家建国流程由服务端从真实玩家、FTB Team 和当前位置推导身份，不接受调用者提交 Team UUID、所有者或首都坐标：

```text
/civic economy nation apply
/civic economy nation status
/civic economy nation cancel <reason>
/civic economy nation activate
/civic economy nation population
/civic economy nation role list
/civic economy nation role grant <playerUuid> <permission> <reason>
/civic economy nation role revoke <grantUuid> <reason>
/civic economy nation mint start <requestId> <mintId> <periodId> <amountMinorUnits>
/civic economy nation mint status [batchId]
/civic economy nation mint cancel <batchId> <requestId> <reason>
/civic economy nation territory allowance
/civic economy nation territory prepare <requestId>
/civic economy nation territory restore <requestId>
/civic economy nation territory cancel <permitUuid> <requestId> <reason>
```

激活时玩家必须是绑定 FTB Team 的负责人，当前位置所在区块必须已由同一 Team 在 FTB Chunks 中占领。正式世界必须满足有效候选人门槛；只有永久标记的 `DEBUG WORLD` 才能使用单人绕过。

`nation status` 会领取并显示当前申请可归属的 Candidate Online Evidence，报告有效候选人数、当前门槛、FORMAL/DEBUG WORLD 模式和到期时间。到期申请由后台 SQLite 处理器自动转换为 `EXPIRED`；常规服务器 tick 不执行数据库查询。

`nation population` 从正式 Citizenship 历史、Citizenship Correction Grace 和近 60 天已完成在线区间计算可解释的 Effective Citizen 人口；它显示每位 Citizen 的归属在线毫秒数、贡献值、有效人数和人口当量，不使用原始 FTB Team 成员数。

`nation role` 管理精确的国家财政权限。只有实时 FTB Team owner、同时具有未暂停的正式 Citizenship 时才能授予或撤销；目标 UUID 必须是同一 Nation 的有效 Citizen。授权和撤销都持久化审计，普通 FTB 等级不会自动获得财政权限。可用权限包括账户/账本查看、预算编制/批准、付款发起/批准、提现、领土财政、发行、财政角色、公共政策和恢复管理。

`nation mint start` 只接受稳定请求 ID、Registered Mint ID、Issuance Quota Period ID 和面值。服务端从真实玩家、FTB Team、Registered Mint、锁定 Recipe Version、当前 Effective Territory 与玩家库存推导 Nation、位置和材料清单，并要求精确 Nation `MANAGE_ISSUANCE`；重复请求不得改变 Mint、Period、操作者或金额。`cancel` 仅返还该批次真实托管材料并在确认返还后释放额度。处理截止后，服务端先持久化发行 intent，再用同一 operation UUID 向精确 National Treasury 执行真实 LC deposit，幂等确认材料消费，最后在单一 SQLite 事务中将 reserved quota 转为 used、写入唯一 `ISSUANCE` Monetary Supply event 并释放 Registered Mint；启动和每分钟恢复会继续处理所有 pending 窗口。`status` 只读取真实玩家 UUID 所拥有的最新或指定 Batch，显示 Batch/custody/issuance 阶段、处理截止、原始理由和外部 LC/材料审计引用，不能查看其他玩家的 Batch，也不能将普通 LC 转账当作发行。

`nation territory allowance` 使用查询时刻生效的持久化领土政策与同一时刻的正式 Effective Citizen 人口，显示基础区块、有效 Citizen 数、每人区块数、总免费额度和政策版本；它不读取 FTB Team 成员数量。

`nation territory prepare` 和 `restore` 都只接受稳定 `requestId`；服务端从真实玩家、正式 Nation/FTB Team 绑定和玩家当前区块推导目标与金额。`restore` 只处理该精确区块最新的 `SUSPENDED` 维护结论，要求 `MANAGE_TERRITORY_FINANCE`，收取下一个完整周期预付与配置的恢复费，并且不会自动重新启用 FTB force-load。同一请求重放返回原持久化结果，不会再次移动 LC。`cancel` 只退款仍为 READY 的精确 Territory Claim Permit。

FTB Team 成员关系不是 Citizenship。国家激活时创建正式 Citizenship；后续 Team 新成员不会自动入籍。正式 Citizen 离开绑定 Team 后立即停止 Provider 权限和 Effective Citizen 人口贡献，进入当前固定两天的 Citizenship Correction Grace；宽限内回归恢复同一 Citizenship，截止仍未回归才结束 Citizenship 并开始转籍冷却。协调扫描只在服务器线程读取 FTB 快照，所有持久化工作均在 SQLite 线程执行。

受信任 OP/控制台服务管理入口位于：

```text
/civic economy admin service list
/civic economy admin service show <service>
/civic economy admin service register ...
/civic economy admin service grant ...
/civic economy admin service revoke ...
/civic economy admin service disable ...
/civic economy admin service enable ...
/civic economy admin mint status [batchId]
/civic economy admin mint recovery
/civic economy admin backup status
/civic economy admin backup trigger <requestId> <reason>
/civic economy admin backup restore status
/civic economy admin backup restore stage <backupOperationUuid> <requestId> <reason>
/civic economy admin backup restore cancel <restoreOperationUuid> <requestId> <reason>
/civic economy admin territory policy show
/civic economy admin territory policy schedule <baseChunks> <chunksPerEffectiveCitizen> <effectiveAtEpochMillis> <requestId> <reason>
/civic economy admin territory pricing show
/civic economy admin territory pricing schedule <firstOverageChunkCost> <additionalMarginalCost> <effectiveAtEpochMillis> <requestId> <reason>
/civic economy admin territory maintenance show
/civic economy admin territory maintenance schedule <cycleDurationMillis> <baseMaintenancePerClaim> <enclaveMultiplierBasisPoints> <forceLoadSurcharge> <restorationFee> <restorationCooldownMillis> <destructionBasisPoints> <effectiveAtEpochMillis> <requestId> <reason>
```

数据库备份和恢复命令只允许 OP/控制台使用。服务器启动时会恢复未完成的备份操作并创建在线快照，此后每 30 分钟以及正常关服前各排队一次；所有在线 SQLite 和文件工作都在 `Civic-Economy-SQLite` 执行。快照写入 `<world>/civiceconomy/backups`，验证世界/依赖身份和当前 schema 后才原子发布，保留最新 8 份；操作状态、失败、SHA-256、大小和轮换均持久化审计。`trigger` 的管理员身份来自真实命令源，同一管理员的 `requestId` 可安全重放，不能更改理由。

`backup restore stage` 只暂存一份仍为 `COMMITTED` 的精确备份，并将它固定在轮换范围之外；它绝不在线替换正在使用的数据库。恢复只在下一次服务器启动、权威 SQLite 尚未打开时执行：重新验证来源后先创建当前数据库的回滚快照，再通过带持久化激活标记的候选库完成原子切换；`cancel` 只取消尚未启动激活的 `STAGED` 请求。SQLite 快照不是完整世界备份：LC 财政账户和交易标记位于世界 `SavedData`，因此生产恢复必须同时使用刻意匹配的完整世界备份，不能把单独回滚 Civic SQLite 当成跨存储事务。

领土政策命令仅允许 OP/控制台。免费额度与凸性扩张定价都必须安排在未来时刻生效，并持久化操作者、稳定 request ID、参数、理由和生效时间；在管理员安排首个版本前，两者均使用保守的零值默认，因此不会自动产生收费或免费扩张权。

单人集成服务器中，拥有作弊权限的世界所有者使用以下二次确认流程永久启用调试世界：

```text
/civic debug status
/civic debug enable
/civic debug enable confirm
```

专用服务器默认不注册调试写命令。只有在 JVM 启动参数显式加入 `-Dciviceconomy.allowDedicatedDebugWorld=true` 时才注册，并且仍只允许玩家 OP 操作；普通配置文件不能启用该权限。`DEBUG WORLD` 数据不得转成正式经济数据。

## 给另一个 AI 的起始提示

> 请先完整阅读 `README.md`、`AGENTS.md`、v1.1 主规格、`CONTEXT.md` 和全部 ADR。严格只完成第一阶段 NeoForge 工程骨架、依赖版本探针与测试，不实现业务功能。所有集成结论必须来自实际源码、构建或运行证据；完成后提交代码、验证记录和下一阶段接口建议。
