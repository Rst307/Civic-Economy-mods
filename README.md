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

Budget Disbursement 匹配世界进程重启演练为每个崩溃窗口分别使用一个干净的 `run/world`。第一组在真实 LC 已生效并刷盘、SQLite 仍为 `PREPARED` 时以退出码 `88` 终止；第二组在 SQLite 已持久化 `EXTERNAL_APPLIED` 后以退出码 `89` 终止。每组的 `verify` 都必须紧接着复用同一个世界：

```powershell
.\gradlew.bat runGameTestServer -PbudgetDisbursementRestartDrill=prepare-external --no-daemon --console=plain
.\gradlew.bat runGameTestServer -PbudgetDisbursementRestartDrill=verify --no-daemon --console=plain

# 删除任务自有的 run/world，开始第二个独立窗口
.\gradlew.bat runGameTestServer -PbudgetDisbursementRestartDrill=prepare-external-applied --no-daemon --console=plain
.\gradlew.bat runGameTestServer -PbudgetDisbursementRestartDrill=verify --no-daemon --console=plain
```

National Treasury Withdrawal 匹配世界进程重启演练同样为两个崩溃窗口分别使用干净的 `run/world`。第一组在真实国库扣款及其 marker 已刷盘、玩家现金尚未交付时以退出码 `90` 终止；第二组在玩家 inventory 现金与交付 marker 已刷盘、SQLite 仍为 `PREPARED` 时以退出码 `91` 终止。每组的 `verify` 必须紧接着复用同一个世界：

```powershell
.\gradlew.bat runGameTestServer -PtreasuryWithdrawalRestartDrill=prepare-debit --no-daemon --console=plain
.\gradlew.bat runGameTestServer -PtreasuryWithdrawalRestartDrill=verify --no-daemon --console=plain

# 删除任务自有的 run/world，开始第二个独立窗口
.\gradlew.bat runGameTestServer -PtreasuryWithdrawalRestartDrill=prepare-delivery --no-daemon --console=plain
.\gradlew.bat runGameTestServer -PtreasuryWithdrawalRestartDrill=verify --no-daemon --console=plain
```

Nation Activation 匹配世界进程重启演练在真实 LC National Treasury 已创建并刷盘、Civic SQLite 仍为 `PREPARED` 时以退出码 `92` 终止；`verify` 必须紧接着复用同一个世界：

```powershell
.\gradlew.bat runGameTestServer -PnationActivationRestartDrill=prepare-treasury --no-daemon --console=plain
.\gradlew.bat runGameTestServer -PnationActivationRestartDrill=verify --no-daemon --console=plain
```

National Treasury Permanent Destruction 匹配世界进程重启演练为两个崩溃窗口分别使用一个干净的 `run/world`。第一组在真实 LC 国库扣减及幂等 marker 已刷盘、SQLite 尚未记录外部结果时以退出码 `93` 终止；第二组在 SQLite 已记录外部结果、累计净发行量尚未提交时以退出码 `94` 终止。每组的 `verify` 必须紧接着复用该组的同一世界；开始第二组前只删除任务自有的 `run/world`：

```powershell
.\gradlew.bat runGameTestServer -PpermanentDestructionRestartDrill=prepare-external --no-daemon --console=plain
.\gradlew.bat runGameTestServer -PpermanentDestructionRestartDrill=verify --no-daemon --console=plain

# 删除任务自有的 run/world，开始第二个独立窗口
.\gradlew.bat runGameTestServer -PpermanentDestructionRestartDrill=prepare-external-recorded --no-daemon --console=plain
.\gradlew.bat runGameTestServer -PpermanentDestructionRestartDrill=verify --no-daemon --console=plain
```

匹配的完整世界 + Civic SQLite 回滚演练先保存一个一致快照，再完成后续真实永久销毁并以退出码 `95` 停止。服务器停止后运行专用离线恢复任务，随后用同一恢复世界验证；该任务只操作仓库 `run/` 下的任务自有目录，并拒绝路径逃逸与符号链接：

```powershell
.\gradlew.bat runGameTestServer -PmatchedWorldRollbackDrill=prepare --no-daemon --console=plain
.\gradlew.bat restoreMatchedWorldRollbackDrill --no-daemon --console=plain
.\gradlew.bat runGameTestServer -PmatchedWorldRollbackDrill=verify --no-daemon --console=plain
```

这些故障开关同时要求显式 Gradle 属性和真实 `GameTestServer` 类，普通客户端或专用服务器不会触发。每个演练使用独立 GameTest namespace，因此 matched-world `verify` 只重跑对应恢复测试，不会让其他测试用旧世界数据重新初始化。

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
/civic economy nation strength status
/civic economy nation role list
/civic economy nation role grant <playerUuid> <permission> <reason>
/civic economy nation role revoke <grantUuid> <reason>
/civic economy nation budget create <requestId> <amountMinorUnits> <budgetCode> <expiresAtEpochMillis> <purpose>
/civic economy nation budget approve <budgetId> <requestId> <reason>
/civic economy nation budget disbursement request <budgetId> <requestId> <recipientUuid> <amountMinorUnits> <reason>
/civic economy nation budget disbursement approve <approvalId> <requestId> <reason>
/civic economy nation budget disbursement approval list
/civic economy nation budget disbursement approval status <approvalId>
/civic economy nation budget disbursement approval cancel <approvalId> <requestId> <reason>
/civic economy nation budget disbursement policy status
/civic economy nation budget disbursement policy history
/civic economy nation budget disbursement policy schedule <requestId> <effectiveAtEpochMillis> <approvalLifetimeMillis> <thresholdMinorUnits> <requiredApprovals> <reason>
/civic economy nation budget disbursement policy schedule-tiered <requestId> <effectiveAtEpochMillis> <approvalLifetimeMillis> <tierSpec> <reason>
/civic economy nation budget cancel <budgetId> <requestId> <reason>
/civic economy nation budget list
/civic economy nation budget status <budgetId>
/civic economy nation bill issue <requestId> <payerUuid> <amountMinorUnits> <kind> <dueAtEpochMillis> <purpose>
/civic economy nation bill list
/civic economy nation bill status <billId>
/civic economy nation facility register <requestId> <reason>
/civic economy nation facility interface bind <requestId> <reason>
/civic economy nation facility baseline capture <requestId> <reason>
/civic economy nation facility baseline activate <requestId> <reason>
/civic economy nation facility status
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
/civic economy admin strength effective-citizen show
/civic economy admin strength effective-citizen schedule <fullStrengthScaleCitizenEquivalents> <effectiveAtEpochMillis> <requestId> <reason>
/civic economy admin strength effective-territory show
/civic economy admin strength effective-territory schedule <fullStrengthScaleEffectiveClaims> <effectiveAtEpochMillis> <requestId> <reason>
```

激活时玩家必须是绑定 FTB Team 的负责人，当前位置所在区块必须已由同一 Team 在 FTB Chunks 中占领。正式世界必须满足有效候选人门槛；只有永久标记的 `DEBUG WORLD` 才能使用单人绕过。

`nation status` 会领取并显示当前申请可归属的 Candidate Online Evidence，报告有效候选人数、当前门槛、FORMAL/DEBUG WORLD 模式和到期时间。到期申请由后台 SQLite 处理器自动转换为 `EXPIRED`；常规服务器 tick 不执行数据库查询。

`nation population` 从正式 Citizenship 历史、Citizenship Correction Grace 和近 60 天已完成在线区间计算可解释的 Effective Citizen 人口；它显示每位 Citizen 的归属在线毫秒数、贡献值、有效人数和人口当量，不使用原始 FTB Team 成员数。National Strength 快照复用同一权威人口计算，以平方根边际递减将人口当量归一化；满量程所需人口当量由延迟生效、不可变且可审计的 Effective Citizen Strength Policy 决定。新世界在首个政策生效前失败关闭该组件，不采用隐藏默认值，也不改写已有 Citizenship 或在线证据。

Effective Territory 同样使用延迟生效、不可变且可审计的 Effective Territory Strength Policy 决定满分所需 Effective Claim 数。首个版本生效前，领土组件保持 `PAUSED_ANOMALY` 和 0 分，但仍显示真实 current/EFFECTIVE/SUSPENDED/未评估 Claim 数；后续版本只影响后续快照，不改写 FTB Claim 或领土财政历史。

`nation strength status` 显示同一快照中的 Effective Citizen basis points、有效人数、人口当量、当前/EFFECTIVE/SUSPENDED/未评估 Claim 数、Effective Territory basis points、30 天 Mint Compliance basis points 与 observation 分类、30 天可审计活动 basis points、30 天滚动 Production Marginal Return、接受/排除计数、总国力和新铸币暂停状态。领土只统计当前仍由精确 FTB Team 占领且最新财政结论为 EFFECTIVE 的区块。Mint Compliance 每个 Batch 最多形成一个 observation：无事故完成为 `CLEAN_COMMIT`（100%），事故解决且 Batch 最终完成为 `RECOVERED_COMMIT`（50%），仍有事故为 `OPEN_INCIDENT`（0 且暂停），事故虽解决但 Batch 尚未 `COMMITTED` 为 `QUARANTINED_RECOVERY`（0 且暂停）；无样本时合规保持 ACTIVE、0 分。生产贡献按证据时刻排序，最近 7 天满权重，之后衰减到 30 天窗口边界，并连续累计单设施和单产业软上限；Create 缺失/不兼容，或窗口内存在已出口但没有完整价格、产业和边际策略绑定的真实 `INCLUDED` 观察时，生产/基础设施保持 `PAUSED_ANOMALY`。

`nation role` 管理精确的国家财政权限。只有实时 FTB Team owner、同时具有未暂停的正式 Citizenship 时才能授予或撤销；目标 UUID 必须是同一 Nation 的有效 Citizen。授权和撤销都持久化审计，普通 FTB 等级不会自动获得财政权限。可用权限包括账户/账本查看、预算编制/批准、付款发起/批准、提现、领土财政、设施核算、发行、财政角色、审批策略、公共政策和恢复管理。设施范围、核算接口和基线管理使用独立的 `MANAGE_FACILITY_ACCOUNTING`，不同时授予领土、国库支出或铸币权限。

`nation facility register` 只接受稳定请求 ID 和审计理由。服务端从真实玩家、当前非玩家 FTB Team、正式 Citizenship/Nation、玩家当前位置、当前 FTB Claim 所有权和当前领土财政结论推导设施身份、核心位置与初始单区块范围，并要求精确的 `MANAGE_FACILITY_ACCOUNTING`；调用者不能提交 Nation、Team、设施 UUID、坐标或 Claim。`nation facility interface bind` 同样只接受请求 ID 和理由，绑定玩家五格内看向、脚所在或脚下的真实 Civic Facility Accounting Interface；服务端同时校验精确方块、方块实体、设施范围、Nation/Team 绑定和权限，普通箱子或伪造坐标不能注册。同一请求重放不会创建第二个设施或接口。

`nation facility baseline capture` 只接受稳定请求 ID 和审计理由。运行时先在 SQLite 单写线程鉴权并定位当前设施和接口，再在 Minecraft 服务器线程读取已加载区块中的真实 Civic 接口库存与受支持 Create `6.0.6` 固定机器，最后回到写线程重新验证 Citizenship、FTB Team、Nation、权限、设施、接口和 Effective Territory 后持久化 `CAPTURED` 基线。它不会强制加载区块，也不接受玩家提交机器、库存或生产结论；Create 缺失/不兼容、区块未加载或事实变化均失败关闭且不写基线。同一请求重放直接返回原基线，不再读取可变世界。

`nation facility baseline activate` 使用相同的三阶段线程边界，激活前重新读取真实世界并要求 Create 版本、接口和固定机器集合与已捕获基线一致；库存数量可以变化，但不会改写原始起点。成功后基线和 Registered Facility 在同一 SQLite 事务中变为 `ACTIVE`。`nation facility status` 只读取当前玩家所在设施的服务端权威 Facility、Interface 和 Baseline 状态，要求同一精确权限，不读取 Create 世界也不推进状态。

激活后的 Civic Facility Accounting Interface 会在服务器 tick 中比较真实 27 槽库存前后状态，只把按物品与组件身份计算的全接口净增加量形成 Facility Accounting Receipt；接口内部换槽、拆堆或合堆不会伪造入库。Create `6.0.6` 磨石的真实配方完成先进入五秒保守匹配窗口，SQLite 写线程再通过注册设施、精确接口、活动基线、固定机器和 Effective Territory 规则选择最早的精确完成并原子保存 Completion、Receipt 和 Decision。同一 Completion 或 Receipt 不能重复消费，玩家和命令没有提交产物事实的入口；崩溃只可能漏掉尚未提交的短时证据，不能产生重复或虚假贡献。

服务器启动时及此后每分钟会重新核对活动设施全部范围区块的真实 FTB Claim 与最新财政有效性。任一范围区块不再属于该设施绑定的 Team，或不再是 Effective Territory，设施就会以持久化审计转为 `PAUSED_TERRITORY`；全部范围恢复且原 Baseline 仍为 `ACTIVE` 后才会自动恢复为 `ACTIVE`。暂停不会删除或修改 FTB Claim、Facility Accounting Baseline、历史 Completion、Receipt 或 Decision，重复扫描也不会重复写同一状态变化。SQLite 列表和状态提交只在单写线程执行，真实 FTB 世界事实只在 Minecraft 服务器线程读取。

`nation budget create` 创建一条不移动 LC、也不创建 Reservation 或 Escrow 的 National Treasury Budget draft。服务端从真实命令玩家、当前 FTB Team、正式 Citizenship/Nation 和稳定 NationId 推导精确来源国库，并在注册内部 Budget 服务前要求该 Citizen 具有本国 `DRAFT_BUDGET`。命令只接受稳定请求 ID、正金额、预算代码、未来到期时间和用途；调用者不能提交 Nation、Team 或来源账户。同一请求重放返回原 draft，改变任一字段会冲突。

`nation budget list|status` 从真实命令玩家的 effective Citizenship 推导 Nation，要求精确 `VIEW_ACCOUNT`，并只返回 source 为本国 National Treasury 的 Budget。列表按到期时间和 Budget UUID 稳定排序；foreign 与 unknown Budget UUID 使用同一失败路径。输出包含来源、总额、已结算额、剩余额、预算代码、状态、到期时间、Escrow 和用途；读取不会批准 Budget、创建 Reservation/Escrow、移动 LC 或推进状态。

`nation budget approve` 允许具有本国 `APPROVE_BUDGET` 的 effective Citizen 批准本国 National Treasury 的 `DRAFT`。同一 Citizen 可以编制并批准；服务端先校验真实玩家、FTB Team、Citizenship、Nation、权限和 Budget source，再在服务器线程读取真实 LC Treasury 余额，最后于 SQLite 单写线程原子创建唯一 Reservation/Escrow 并推进为 `APPROVED`。批准只预占可用额度，不移动 LC；actor、reason、request 和时间持久化审计，同 request replay 不会创建第二个 hold，改变 Budget、actor 或 reason 会冲突。

`nation budget disbursement request` 允许具有本国 `INITIATE_PAYMENT` 的 effective Citizen 从一个本国已批准 Budget 发起精确拨款。服务端从真实玩家、FTB Team、Citizenship/Nation 和持久化 Budget 派生 National Treasury，只接受收款玩家 UUID、正金额、稳定 request ID 和理由；审批对象固定 Budget、收款账户、金额、发起人、政策版本、所需人数和到期时间。多个活动审批不能累计超过 Budget 的未结算 Reservation 余量。发起人自动投第一票；若固定门槛为一人，系统立即进入既有 SQLite `PREPARED` → 服务器线程真实 LC 转账 → SQLite `CIVIC_COMMITTED` 路径，并原子推进 Reservation、Escrow、Budget 与审批 `EXECUTED`。启动和每分钟恢复扫描按精确内部 Service Identity 覆盖两个崩溃窗口：审批已持久化为 `APPROVED` 但尚未创建 Payment 时补建同一稳定 Payment；Payment 已存在于 `PREPARED` 或 `EXTERNAL_APPLIED` 时保留原 transaction UUID 与 LC applied marker，只在服务器线程执行或确认真实 LC，再由 SQLite 单写线程原子推进 Payment、Reservation、Escrow、Budget 与审批。单条失败不会阻断其他工作，另一个财政服务的 session 即使拥有同一国库授权也不能扫描或恢复这些 Payment。

`nation budget disbursement approve` 允许另一名具有同一 Nation `APPROVE_PAYMENT` 的 effective Citizen 为仍为 `PENDING` 的精确审批投票；同一 Citizen 不能重复计票，达到发起时固定的人数后才会创建 Payment。`PENDING` 审批到期时由后台 SQLite 扫描转为 `EXPIRED`，不会创建 Payment 或调用 LC，并释放其占用的 Budget 授权容量。

`nation budget disbursement approval list|status` 从真实命令玩家的 effective Citizenship 派生 Nation，并额外要求该 Nation 的精确 `APPROVE_PAYMENT`。列表只返回本国审批并按发起时间和审批 UUID 倒序稳定排列；foreign 与 unknown 审批 UUID 使用同一失败路径。状态显示固定的 Budget、收款账户、金额、发起人、理由、策略版本、所需人数、逐票操作者/理由/时间、到期与执行证据，以及当前玩家是否仍可投票。读取不会创建 Payment、投票、移动 LC 或推进审批/Budget 状态。

`nation budget disbursement approval cancel` 允许具有同一 Nation 精确 `APPROVE_PAYMENT` 的 effective Citizen 使用稳定 request ID 取消仍为 `PENDING` 且尚未创建 Payment 的本国审批。服务端先从真实玩家和 effective Citizenship 派生 Nation，再以与 unknown UUID 相同的路径拒绝 foreign approval；SQLite 追加不可变 actor/reason/time 取消审计，并在读取时把原审批事实叠加显示为 `CANCELLED`。取消会立即释放该审批占用的 Budget 授权容量，但不会释放 Reservation、改变 Escrow 或 Budget 已结算状态、创建 Payment、调用 LC 或移动任何余额；严格重放不会重复释放容量，改变审批、操作者或理由会冲突，已取消审批不能再投票或执行。

`nation budget disbursement policy status|history` 只要求真实玩家拥有 effective Citizenship，并从服务端派生其 Nation。状态返回查询时刻生效的策略；历史按生效时间、记录时间和策略 UUID 稳定排列，包含当前和未来版本、金额门槛、审批人数、有效期、操作者、理由与时间。读取不会激活未来策略或产生 SQLite 写入。

`nation budget disbursement policy schedule|schedule-tiered` 需要同一 Nation 的 `MANAGE_APPROVAL_POLICY`，并只允许未来生效、正数审批有效期和每层 1–16 名审批人。简单命令中，`thresholdMinorUnits=0` 表示所有拨款使用指定人数；正阈值表示低于阈值保持单人、达到或超过阈值使用指定人数。多层命令的 `tierSpec` 使用未加空格的 `minimumAmount:requiredApprovals` 逗号序列，例如 `0:1,500:2,2000:3`；第一层必须从零开始，金额严格递增，格式、负数、溢出和越界人数都会在命令解析时拒绝。策略版本、完整层级、操作者、理由、生效时间和有效期持久化审计；稳定 request ID 严格重放，每个新审批只固定创建时生效的版本和对应金额层级，后续策略不能改写旧审批。

OP/控制台的 `admin budget-disbursement recovery status` 只读取精确内部服务下的 `APPROVED`-without-Payment 决定和已有 `PREPARED`/`EXTERNAL_APPLIED` Payment，显示固定审批、transaction、账户、金额与状态证据；该读取不打开执行 session、不创建或推进 Payment，也不调用 LC，自动生命周期恢复仍是唯一执行路径。

`nation budget cancel` 允许具有本国 `APPROVE_BUDGET` 的 effective Citizen 取消本国 National Treasury 中仍为 `APPROVED` 或 `PARTIALLY_SPENT` 的 Budget。服务端从真实玩家、FTB Team、Citizenship 和 Nation 推导精确 Treasury，在注册内部服务前拒绝 foreign Budget；SQLite 在一个事务中记录 actor/reason/time 审计、释放唯一 Reservation 和 Escrow、保留已结算金额并把 Budget 推进为 `RELEASED`。取消只释放未支付逻辑 hold，不移动 LC；存在 `PREPARED`、`EXTERNAL_APPLIED` 或补偿中的 Payment 时失败关闭，同 request replay 不会产生第二次 release。

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
/civic economy admin budget-disbursement recovery status
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
/civic economy admin reference-price show <itemId> "<componentFingerprint>"
/civic economy admin reference-price schedule <itemId> "<componentFingerprint>" <unitPriceMinorUnits> <effectiveAtEpochMillis> <requestId> <reason>
/civic economy admin production marginal-return show
/civic economy admin production marginal-return schedule <facilitySoftCapMinorUnits> <facilityExcessWeightBasisPoints> <industrySoftCapMinorUnits> <industryExcessWeightBasisPoints> <effectiveAtEpochMillis> <requestId> <reason>
/civic economy admin production strength-policy show
/civic economy admin production strength-policy schedule <observationWindowMillis> <fullWeightWindowMillis> <fullStrengthScaleMinorUnits> <effectiveAtEpochMillis> <requestId> <reason>
/civic economy admin production industry show <createVersion> <recipeId>
/civic economy admin production industry schedule <createVersion> <recipeId> <industryId> <effectiveAtEpochMillis> <requestId> <reason>
```

数据库备份和恢复命令只允许 OP/控制台使用。服务器启动时会恢复未完成的备份操作并创建在线快照，此后每 30 分钟以及正常关服前各排队一次；所有在线 SQLite 和文件工作都在 `Civic-Economy-SQLite` 执行。快照写入 `<world>/civiceconomy/backups`，验证世界/依赖身份和当前 schema 后才原子发布，保留最新 8 份；操作状态、失败、SHA-256、大小和轮换均持久化审计。`trigger` 的管理员身份来自真实命令源，同一管理员的 `requestId` 可安全重放，不能更改理由。

`backup restore stage` 只暂存一份仍为 `COMMITTED` 的精确备份，并将它固定在轮换范围之外；它绝不在线替换正在使用的数据库。恢复只在下一次服务器启动、权威 SQLite 尚未打开时执行：重新验证来源后先创建当前数据库的回滚快照，再通过带持久化激活标记的候选库完成原子切换；`cancel` 只取消尚未启动激活的 `STAGED` 请求。SQLite 快照不是完整世界备份：LC 财政账户和交易标记位于世界 `SavedData`，因此生产恢复必须同时使用刻意匹配的完整世界备份，不能把单独回滚 Civic SQLite 当成跨存储事务。

`admin mint correction apply` 只接受一个 `OPEN TREASURY_CREDIT` Mint Recovery Incident、稳定 request ID、独立证据引用和理由；管理员身份从真实 OP/控制台命令源派生。金额、Batch、operation、Nation、账户和方向全部由持久化 Incident 与 Mint Issuance Operation 推导，命令不接受这些值。校正按该发行周期已持久化的硬上限执行，只增加缺失的累计净发行记录并把 Incident 解决为 `STOCK_CORRECTION`；它不移动 LC、不释放额度、不恢复 Registered Mint、不完成 Batch，材料与额度继续隔离。证据引用包含空格或冒号时应使用引号；`status` 按 Incident UUID 返回不可变校正结果。

领土政策命令仅允许 OP/控制台。免费额度与凸性扩张定价都必须安排在未来时刻生效，并持久化操作者、稳定 request ID、参数、理由和生效时间；在管理员安排首个版本前，两者均使用保守的零值默认，因此不会自动产生收费或免费扩张权。

参考价命令同样只允许 OP/控制台。它按精确物品 ID 和组件指纹安排未来生效的全服统一参考价，服务端自动记录真实管理员身份、request ID、理由和时间；相同请求只能重放原版本，改变参数会失败。没有已生效版本的物品或组件身份返回零贡献。生产国力只接受从真实 Receipt、出口谱系、证据时刻价格、产业归类和边际策略形成的完整绑定，不接受设施所有者或玩家提交价格或评分。

生产国力政策命令只允许 OP/控制台，并把滚动观察窗口、满权重区间和满分尺度安排为未来生效的全服版本。National Strength 在每次评估时读取当时生效的版本；没有版本时生产组件保守暂停且不持久化伪造评估，后续版本也不会改写旧生产证据绑定。

单人集成服务器中，拥有作弊权限的世界所有者使用以下二次确认流程永久启用调试世界：

```text
/civic debug status
/civic debug enable
/civic debug enable confirm
```

专用服务器默认不注册调试写命令。只有在 JVM 启动参数显式加入 `-Dciviceconomy.allowDedicatedDebugWorld=true` 时才注册，并且仍只允许玩家 OP 操作；普通配置文件不能启用该权限。`DEBUG WORLD` 数据不得转成正式经济数据。

## 给另一个 AI 的起始提示

> 请先完整阅读 `README.md`、`AGENTS.md`、v1.1 主规格、`CONTEXT.md` 和全部 ADR。严格只完成第一阶段 NeoForge 工程骨架、依赖版本探针与测试，不实现业务功能。所有集成结论必须来自实际源码、构建或运行证据；完成后提交代码、验证记录和下一阶段接口建议。
