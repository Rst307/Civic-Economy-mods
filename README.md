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

Mint 匹配世界进程重启演练使用同一 `run/world` 连续执行两轮。第一轮预期以进程退出码 `86` 终止，不能当作普通测试失败；第二轮必须完整通过：

```powershell
.\gradlew.bat runGameTestServer -PmintRestartDrill=prepare-external --no-daemon --console=plain
.\gradlew.bat runGameTestServer -PmintRestartDrill=verify --no-daemon --console=plain
```

`prepare-external` 预期退出码为 `86`。另起一个干净世界后，可验证材料消费刷盘窗口；第一轮预期退出码为 `87`：

```powershell
.\gradlew.bat runGameTestServer -PmintRestartDrill=prepare-materials --no-daemon --console=plain
.\gradlew.bat runGameTestServer -PmintRestartDrill=verify --no-daemon --console=plain
```

该故障开关同时要求显式 Gradle 属性和真实 `GameTestServer` 类，普通客户端或专用服务器不会触发。

`runServer` 默认验证不安装 Create 的财政核心；传入 `-PincludeCreate=true` 才加载可选 Create 适配环境。真实验证结果与证据类型持续记录在 `docs/IMPLEMENTATION_STATUS.md`。

在受支持的 Lightman’s Currency `1.21-2.3.0.5` 运行时中，Civic Economy 会强制关闭 LC 原生 Coin Mint 铸造/熔化、实体与箱子免费货币掉落、村民/流浪商人货币交易、季节奖励和银行利息，并拒绝 `/give` LC 货币与 `/lcbank give`。该保护不改写管理员保存的 LC 配置文件，而是在运行时强制安全读值并在实际发行方法处再次拒绝；升级 LC 版本必须重新审查这些精确边界并重跑 GameTest。

## 当前游戏内命令

玩家建国流程由服务端从真实玩家、FTB Team 和当前位置推导身份，不接受调用者提交 Team UUID、所有者或首都坐标：

```text
/civic economy bill list
/civic economy bill status <billId>
/civic economy bill fund <billId> <requestId>
/civic economy bill pay <billId> <requestId>
/civic economy bill cancel <billId> <requestId> <reason>
/civic economy nation apply
/civic economy nation status
/civic economy nation cancel <reason>
/civic economy nation activate
/civic economy nation population
/civic economy nation role list
/civic economy nation role grant <playerUuid> <permission> <reason>
/civic economy nation role revoke <grantUuid> <reason>
/civic economy nation budget create <requestId> <amountMinorUnits> <budgetCode> <expiresAtEpochMillis> <purpose>
/civic economy nation budget approve <budgetId> <requestId> <reason>
/civic economy nation budget list
/civic economy nation budget status <budgetId>
/civic economy nation bill issue <requestId> <payerUuid> <amountMinorUnits> <kind> <dueAtEpochMillis> <purpose>
/civic economy nation bill list
/civic economy nation bill status <billId>
/civic economy nation mint start <requestId> <mintId> <periodId> <amountMinorUnits>
/civic economy nation mint status [batchId]
/civic economy nation mint cancel <batchId> <requestId> <reason>
/civic economy nation treasury withdraw <requestId> <amountMinorUnits> <reason>
/civic economy nation treasury withdraw approve <approvalRequestId> <requestId> <reason>
/civic economy nation treasury withdraw approval list
/civic economy nation treasury withdraw approval status <approvalRequestId>
/civic economy nation treasury withdraw approval cancel <approvalRequestId> <requestId> <reason>
/civic economy nation treasury withdraw policy status
/civic economy nation treasury withdraw policy history
/civic economy nation treasury withdraw policy schedule <requestId> <effectiveAtEpochMillis> <approvalLifetimeMillis> <thresholdMinorUnits> <requiredApprovals> <reason>
/civic economy nation treasury withdraw policy schedule-tiered <requestId> <effectiveAtEpochMillis> <approvalLifetimeMillis> <tierSpec> <reason>
/civic economy nation treasury destroy <requestId> <amountMinorUnits> <reason>
/civic economy nation territory allowance
/civic economy nation territory prepare <requestId>
/civic economy nation territory restore <requestId>
/civic economy nation territory cancel <permitUuid> <requestId> <reason>
```

激活时玩家必须是绑定 FTB Team 的负责人，当前位置所在区块必须已由同一 Team 在 FTB Chunks 中占领。正式世界必须满足有效候选人门槛；只有永久标记的 `DEBUG WORLD` 才能使用单人绕过。

`nation status` 会领取并显示当前申请可归属的 Candidate Online Evidence，报告有效候选人数、当前门槛、FORMAL/DEBUG WORLD 模式和到期时间。到期申请由后台 SQLite 处理器自动转换为 `EXPIRED`；常规服务器 tick 不执行数据库查询。

`nation population` 从正式 Citizenship 历史、Citizenship Correction Grace 和近 60 天已完成在线区间计算可解释的 Effective Citizen 人口；它显示每位 Citizen 的归属在线毫秒数、贡献值、有效人数和人口当量，不使用原始 FTB Team 成员数。

`nation role` 管理精确的国家财政权限。只有实时 FTB Team owner、同时具有未暂停的正式 Citizenship 时才能授予或撤销；目标 UUID 必须是同一 Nation 的有效 Citizen。授权和撤销都持久化审计，普通 FTB 等级不会自动获得财政权限。可用权限包括账户/账本查看、预算编制/批准、付款发起/批准、提现、领土财政、发行、财政角色、审批策略、公共政策和恢复管理。

`nation budget create` 创建一条不移动 LC、也不创建 Reservation 或 Escrow 的 National Treasury Budget draft。服务端从真实命令玩家、当前 FTB Team、正式 Citizenship/Nation 和稳定 NationId 推导精确来源国库，并在注册内部 Budget 服务前要求该 Citizen 具有本国 `DRAFT_BUDGET`。命令只接受稳定请求 ID、正金额、预算代码、未来到期时间和用途；调用者不能提交 Nation、Team 或来源账户。同一请求重放返回原 draft，改变任一字段会冲突。

`nation budget list|status` 从真实命令玩家的 effective Citizenship 推导 Nation，要求精确 `VIEW_ACCOUNT`，并只返回 source 为本国 National Treasury 的 Budget。列表按到期时间和 Budget UUID 稳定排序；foreign 与 unknown Budget UUID 使用同一失败路径。输出包含来源、总额、已结算额、剩余额、预算代码、状态、到期时间、Escrow 和用途；读取不会批准 Budget、创建 Reservation/Escrow、移动 LC 或推进状态。

`nation budget approve` 允许具有本国 `APPROVE_BUDGET` 的 effective Citizen 批准本国 National Treasury 的 `DRAFT`。同一 Citizen 可以编制并批准；服务端先校验真实玩家、FTB Team、Citizenship、Nation、权限和 Budget source，再在服务器线程读取真实 LC Treasury 余额，最后于 SQLite 单写线程原子创建唯一 Reservation/Escrow 并推进为 `APPROVED`。批准只预占可用额度，不移动 LC；actor、reason、request 和时间持久化审计，同 request replay 不会创建第二个 hold，改变 Budget、actor 或 reason 会冲突。

`nation bill issue` 创建一张不移动 LC、也不创建 Reservation 或 Escrow 的手动 Fiscal Bill。服务端从真实命令玩家、当前 FTB Team、正式 Citizenship/Nation 和稳定 NationId 推导授权与收款方，要求精确 Nation `INITIATE_PAYMENT`，并把 beneficiary 固定为该 Nation 的 National Treasury。命令只接受 payer 的玩家 UUID，来源账户固定派生为 `player:<UUID>`；调用者不能提交 Nation、Team、来源账户或重定向 beneficiary。同一稳定 `requestId` 重放返回原 Bill，改变 payer、金额、种类、用途或到期时间会失败。

`bill list|status` 只按真实命令玩家派生 `player:<UUID>`，列出或读取明确由该玩家付款的 Bill；foreign 与 unknown Bill UUID 使用同一失败路径。`nation bill list|status` 则从当前有效 Citizenship 推导 Nation，要求精确 `VIEW_ACCOUNT`，并只返回 beneficiary 为本国 National Treasury 的 Bill。两套查询都显示 payer、beneficiary、总额、已结算额、剩余额、种类、状态、到期时间、Escrow 和用途，且不会创建 Reservation/Escrow、移动 LC 或推进 Bill 状态。

`bill fund` 只允许真实命令玩家为明确写给自己 `player:<UUID>` 账户的 Bill 建立资金保证。服务端在注册内部 funding 服务或创建 hold 前先按持久化 payer 失败关闭，随后在服务器线程读取真实 LC 玩家银行余额，并在 SQLite 单写线程以精确 `FUND_BILL` 玩家账户作用域创建一次 Reservation 和 recipient-bound Escrow。beneficiary 只能来自持久化 Bill，调用者不能提交 payer、来源账户、Nation、金额或收款方；稳定 `requestId` 重放不会创建第二个 hold。funding 本身不移动 LC，也不等于支付或结算。

`bill pay` 只允许真实 payer 结算已 funding Bill 的全部剩余金额。命令不接受金额、Reservation、来源账户或 recipient；服务端从持久化 Bill/Escrow 派生精确 payer、剩余 hold 和 beneficiary，并以 owner-bound 内部服务的精确 `SETTLE_PAYMENT` 玩家账户作用域执行。SQLite 先持久化 `PREPARED`，真实 LC 转账只在服务器线程执行，随后 SQLite 单写线程确认外部效果并原子推进 Payment、Reservation、Escrow、Bill 和成对账本；同一 `requestId` 重放只补齐缺失阶段，不会再次扣款。

`bill cancel` 只允许真实 payer 取消已 funding、仍可安全释放的 Bill。命令不接受 payer、Reservation、账户、金额或释放目标；服务端从持久化 Bill/Escrow 派生精确玩家账户与 hold，并以 owner-bound 内部服务的精确 `RESERVE_FUNDS` 账户作用域释放未支付余量。取消不会移动 LC，已结算金额保持不变，Bill/Escrow/Reservation 在同一 SQLite 事务中推进；存在 `PREPARED`、`EXTERNAL_APPLIED` 或补偿中付款时失败关闭，同一 `requestId` 重放不会产生第二次 release。

`nation mint start` 只接受稳定请求 ID、Registered Mint ID、Issuance Quota Period ID 和面值。服务端从真实玩家、FTB Team、Registered Mint、锁定 Recipe Version、当前 Effective Territory 与玩家库存推导 Nation、位置和材料清单，并要求精确 Nation `MANAGE_ISSUANCE`；重复请求不得改变 Mint、Period、操作者或金额。`cancel` 仅返还该批次真实托管材料并在确认返还后释放额度。处理截止后，服务端先持久化发行 intent，再用同一 operation UUID 向精确 National Treasury 执行真实 LC deposit，幂等确认材料消费，最后在单一 SQLite 事务中将 reserved quota 转为 used、写入唯一 `ISSUANCE` Monetary Supply event 并释放 Registered Mint；启动和每分钟恢复会继续处理所有 pending 窗口。`status` 只读取真实玩家 UUID 所拥有的最新或指定 Batch，显示 Batch/custody/issuance 阶段、处理截止、原始理由、外部 LC/材料审计引用，以及最新 Mint Recovery Incident 的步骤、失败类型、消息、次数、时间和解决证据，不能查看其他玩家的 Batch，也不能将普通 LC 转账当作发行。

如果真实 LC 入账或材料消费可能已经发生但无法立即核对，Civic 会保持 Batch、Registered Mint 和额度处于恢复隔离状态，不自动撤回国库、不取消批次、不释放额度。`admin mint recovery` 只重试同一幂等步骤；经独立证据确认的货币偏差必须进入单独的服务器级 Monetary Stock Correction 流程，不能伪装成普通付款、退款或新的 Batch 重试。

`nation territory allowance` 使用查询时刻生效的持久化领土政策与同一时刻的正式 Effective Citizen 人口，显示基础区块、有效 Citizen 数、每人区块数、总免费额度和政策版本；它不读取 FTB Team 成员数量。

`nation territory prepare` 和 `restore` 都只接受稳定 `requestId`；服务端从真实玩家、正式 Nation/FTB Team 绑定和玩家当前区块推导目标与金额。`restore` 只处理该精确区块最新的 `SUSPENDED` 维护结论，要求 `MANAGE_TERRITORY_FINANCE`，收取下一个完整周期预付与配置的恢复费，并且不会自动重新启用 FTB force-load。同一请求重放返回原持久化结果，不会再次移动 LC。`cancel` 只退款仍为 READY 的精确 Territory Claim Permit。

`nation treasury destroy` 是国家国库的永久销毁入口，不是付款、退款、提现或管理员余额调整。命令只接受稳定请求 ID、正金额和审计理由；服务端从真实玩家、当前 FTB Team、正式 Citizenship/Nation 和稳定 NationId 推导精确 National Treasury，并要求该玩家拥有 `MANAGE_ISSUANCE`。调用者不能提交 Nation、Team 或来源账户。真实 LC 扣减、累计净发行量减少、`player:<UUID>` 操作者和请求重放都由持久化 Permanent Destruction 状态机约束；同一请求改变金额、操作者、来源或理由会失败且不会再次销毁。

`nation treasury withdraw` 把精确 National Treasury 的 LC 银行余额等额转换为交付给当前获授权玩家的实体 LC 硬币，不是付款、发行、永久销毁或免费赠款。命令只接受稳定请求 ID、正金额和审计理由；服务端推导真实玩家、FTB Team、正式 Citizenship/Nation 与来源国库，并要求该玩家拥有 `MANAGE_WITHDRAWAL`。请求会按发起时生效的 Withdrawal Approval Policy 固定政策版本、所需人数和到期时间，并自动记录发起人的第一票；人数不足时只保留 `PENDING` 审批，不创建提现操作、不扣 LC。其他具有同一 Nation `MANAGE_WITHDRAWAL` 的正式 Citizen 使用 `withdraw approve` 对精确 approval UUID 审批，同一 Citizen 不能重复计票；达到人数后，提现操作永久绑定该审批决定。具有同一精确权限的当前正式 Citizen 可以用稳定 request ID 取消仍为 `PENDING` 或尚未产生 Withdrawal Operation 的 `APPROVED` 决定；取消只写入不可变审计并转为 `CANCELLED`，不移动 LC、不退款、不释放资金，也不能补偿或改写已有 Operation。首次外部执行会先在目标玩家 inventory 副本上按固定 LC 面额精确模拟容量，容量不足时国库不扣款；持久化操作、国库扣款 marker 和玩家现金交付 marker 共同保证重放只补齐缺失步骤。提现不改变 Cumulative Net Issuance，调用者也不能指定 Nation、Team、来源账户或目标玩家。

`withdraw policy schedule|schedule-tiered` 需要同一 Nation 的 `MANAGE_APPROVAL_POLICY`。策略只能未来生效，`approvalLifetimeMillis` 必须为正。简单命令中，`thresholdMinorUnits=0` 表示所有提现都需要指定人数，正阈值表示低于阈值保持单人、达到或超过阈值需要 `requiredApprovals`（1–16）名不同 Citizen。多层命令的 `tierSpec` 使用未加空格的 `minimumAmount:requiredApprovals` 逗号序列，例如 `0:1,500:2,2000:3`；第一层必须从零开始，金额必须严格递增，每层人数必须为 1–16。策略版本、操作者、全部层级、审批有效时长、生效时间和理由都会持久化审计；相同 request ID 改变任一字段会冲突。每个新审批只读取创建时生效的政策并固定计算出的到期时间，后续策略版本不会改写旧审批。

`withdraw policy status|history` 只从真实玩家当前有效 Citizenship 推导 Nation，任何正式 Citizen 都可查看本国当前政策以及按生效时间排序的全部当前/未来版本，不能提交 Nation 或账户作用域；历史显示操作者、理由、层级、有效时长和时间戳，且不触发政策生效或任何写操作。`withdraw approval list|status` 进一步要求同一 Nation 的精确 `MANAGE_WITHDRAWAL`，只返回本国审批，并显示发起人、金额、策略、所需人数、每名审批人及其理由/时间、`PENDING`/`APPROVED`/`EXECUTED` 状态和当前玩家是否仍可审批；外部 Nation 的真实 approval UUID 也会失败关闭。OP/控制台的 `admin withdrawal recovery status` 只读取已批准但尚未创建 Operation 的决定及仍为 `PREPARED` 的 Operation，不会触发、准备或提交提现，自动恢复仍是唯一执行路径。

每个新 Withdrawal Approval 固定其政策版本和由该版本 `approvalLifetime` 计算出的到期时间。未配置政策的 Nation 使用保守的单人审批、7 天默认值；schema v60 的既有政策在迁移时也回填 7 天。只有一直未达到人数的 `PENDING` 决定会由现有异步 Withdrawal recovery 扫描转为 `EXPIRED`；`APPROVED`、已有 Operation、`EXECUTED` 或已取消决定不会被到期器改写。

所有活动 Escrow 由服务端在启动时和每分钟扫描到期时间。SQLite 查询和释放都只在 `Civic-Economy-SQLite` 单写线程执行；到期会原子记录审计、释放未支付 Reservation 余量，并同步推进关联 Budget 或 Fiscal Bill。存在 `PREPARED`、`EXTERNAL_APPLIED` 或补偿中付款的 Escrow 会保守跳过并留给恢复流程，且不会阻断同一扫描中的其他安全到期对象。该流程不移动 LC，也不会重复释放同一 hold。

同一财政到期扫描也会分类尚未批准的 Budget draft 和尚未 funding 的 Fiscal Bill。到期且仍为 `DRAFT`、没有 Escrow 的 Budget 会记录稳定 `automatic-expiry:<budgetId>` 审计并转为 `EXPIRED`；过期且仍为 `ISSUED`、没有 Escrow 的 Bill 使用对应 `automatic-expiry:<billId>` 审计转为 `EXPIRED`。两种分类都不会创建 Reservation、Escrow 或任何 LC 效果，单个已变化对象也不会阻断后续仍可安全到期的对象。

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
/civic economy admin mint correction apply <incidentId> <requestId> <evidenceReference> <reason>
/civic economy admin mint correction status <incidentId>
/civic economy admin withdrawal recovery status
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

`admin mint correction apply` 只接受一个 `OPEN TREASURY_CREDIT` Mint Recovery Incident、稳定 request ID、独立证据引用和理由；管理员身份从真实 OP/控制台命令源派生。金额、Batch、operation、Nation、账户和方向全部由持久化 Incident 与 Mint Issuance Operation 推导，命令不接受这些值。校正按该发行周期已持久化的硬上限执行，只增加缺失的累计净发行记录并把 Incident 解决为 `STOCK_CORRECTION`；它不移动 LC、不释放额度、不恢复 Registered Mint、不完成 Batch，材料与额度继续隔离。证据引用包含空格或冒号时应使用引号；`status` 按 Incident UUID 返回不可变校正结果。

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
