# Civic Economy

## Founding applications

**Nation Application**:
A pre-Nation founding candidacy bound to one FTB Team and its Candidate Members. It has no permanent NationId, National Treasury, territorial strength, or issuance authority.
_Avoid_: Pending Nation, temporary Nation, provisional Treasury

**PENDING**:
The active Nation Application state in which candidate eligibility evidence may accumulate but Nation activation has not occurred.
_Avoid_: Active Nation, registered Nation

**Candidate Member**:
A player affiliated with one Nation Application for founding eligibility. Candidate membership is not Citizenship and grants no national fiscal or territorial rights.
_Avoid_: Citizen, Effective Citizen, FTB Team member

**Candidate Online Evidence**:
Recent observed online time attributed to one Nation Application for founding eligibility; the same interval cannot support multiple applications, including after cancellation or expiry.
_Avoid_: Citizenship time, reusable playtime, raw team membership

**Effective Candidate**:
A Candidate Member with non-zero Candidate Online Evidence inside the founding observation window.
_Avoid_: FTB Team member, Citizen, inactive candidate

**Nation Activation**:
The one-way founding transition that creates the permanent NationId, formal Citizenship, Capital, and National Treasury after eligibility is satisfied. A DEBUG WORLD may explicitly bypass the minimum Candidate Member threshold; a formal world may not.
_Avoid_: Application creation, automatic team promotion

**Nation Application Expiry Policy**:
A future-effective, audited server rule for how often Civic discovers already-expired Nation Applications. It never changes an Application's persisted expiry deadline or restores expired Candidate Online Evidence.

**Candidate Online Evidence Policy**:
A future-effective, audited server rule defining the recent observation window used when a Nation Application claims Candidate Online Evidence. It never reassigns or releases an already claimed online interval, changes an Application expiry deadline, or permits one interval to support multiple applications.

**Nation Application Lifetime Policy**:
A future-effective, audited server rule defining how long a newly created Nation Application remains pending. The effective lifetime is converted into an immutable persisted expiry deadline at creation; later policy versions never move an existing Application's deadline.

**Nation Founding Candidate Threshold Policy**:
A future-effective, audited server rule defining the minimum Effective Candidate count required to activate a Nation in a formal world. A permanently marked DEBUG WORLD may explicitly bypass the effective threshold to one candidate, while the activation audit retains the formal threshold and the fact that the bypass was used.
_Avoid_: Effective Citizen population window, application lifetime, reusable online time
_Avoid_: application lifetime, expiry extension, candidate evidence reset

Civic Economy 是多国家 Minecraft 服务器的财政与货币领域。它以统一实体货币为基础，区分国家政治身份、财政控制、可审计经济活动与自由现金活动。

## 国家与治理

**国家（Nation）**:
拥有永久 `NationId`、独立国库和财政历史的政治实体，其成员与政治规则由国家系统提供。
_Avoid_: FTB 团队、组织、国库账户

**国家系统（Nation System）**:
定义国家身份、公民、政府权限、外交与强制领土授权的外部领域。
_Avoid_: Civic Economy、FTB Teams

**国家提供者（Nation Provider）**:
向 Civic Economy 提供国家事实的适配边界；临时实现可以由 FTB Teams 支持。
_Avoid_: 国家数据库、经济账户

**国籍（Citizenship）**:
玩家在某一时间对一个国家的唯一正式成员关系，可与居住、经营或通行权不同。
_Avoid_: FTB 好友关系、签证、经营许可

**有效公民（Effective Citizen）**:
在规定滚动窗口内以累计在线时长形成非零国家人口贡献的公民。
_Avoid_: 成员、累计登录玩家、小号

**国籍纠错宽限（Citizenship Correction Grace）**:
正式 Citizen 暂时不在其国家绑定 FTB Team 时的限时不一致状态；宽限期间 Citizenship 尚未结束，但财政权限和人口贡献立即暂停，回归后恢复，截止时仍不一致才转为无国籍。
_Avoid_: 临时退籍、离队即转籍、有效公民

**Effective Citizen Population Policy**:
A future-effective, audited server rule defining the observation window and online time required for one full Effective Citizen contribution. It interprets immutable Citizenship, Correction Grace and online-time evidence at calculation time; it does not rewrite those facts. Missing policy disables dependent population, territory-allocation, maintenance and National Strength calculations rather than selecting a hidden default.

**国籍政策（Citizenship Policy）**:
由受信任服务器治理安排、在未来时刻生效的全服国籍纠错宽限时长、转籍冷却时长与国籍协调频率规则；它解释操作时刻的新协调和新入籍决定，并安排后续自动协调，但不改写既有 Citizenship、已开始的宽限期限或历史离籍事实。
_Avoid_: FTB Team 配置、即时改写国籍、按国家单独冷却

**国籍协调（Citizenship Reconciliation）**:
把正式 Citizenship 与绑定 FTB Team 的当前成员事实进行服务端校验的过程；它可以启动或解除国籍纠错宽限，但不会把新 Team 成员自动登记为 Citizen。
_Avoid_: FTB 成员同步、自动入籍、团队即国家

**在线时长观察政策（Online Time Observation Policy）**:
由受信任服务器治理安排、在未来时刻生效的全服活动在线会话定期保存频率；它限制进程崩溃时可能丢失的尚未保存会话尾部，但不改写已完成在线区间、不推定离线时长，也不取代登录和退出观察。
_Avoid_: 在线奖励策略、人口观察窗口、离线时间估算、自动补时

**国家财政角色（Nation Fiscal Role）**:
一个 Citizen 在单一 Nation 内当前持有的精确财政权限集合；它不继承 FTB 等级，并在该 Citizen 不再具有有效国家归属时停止生效。
_Avoid_: FTB 等级、全局 OP、服务身份

**国家财政权限（Nation Fiscal Permission）**:
国家治理授予某个 Citizen 的单一、明确财政动作权限，例如批准预算、管理提现、管理审批策略或管理领土财政。
_Avoid_: 全局财政访问、模糊官职、账户余额

**设施核算管理权限（Facility Accounting Management Permission）**:
一个 Nation 授予 Citizen 管理 Registered Facility 范围、绑定 Facility Accounting Interface 以及捕获和启用 Facility Accounting Baseline 的精确权限；它不授予领土所有权、国库支出或铸币权限。
_Avoid_: 领土财政权限、铸币权限、全局设施管理、OP 权限

**Withdrawal Approval Policy（提现审批策略）**:
一个 Nation 对 Treasury Withdrawal 按金额门槛规定所需不同 Citizen 审批人数的未来生效版本；发起时命中的版本和人数会被固定，后续策略变化不能改写已发起请求。
_Avoid_: 全服强制双签、即时生效配置、事后改变审批人数

**Withdrawal Approval（提现审批）**:
具有 `MANAGE_WITHDRAWAL` 的正式 Citizen 对一个精确、不可变 Treasury Withdrawal 请求作出的持久化决定；同一 Citizen 对同一请求最多计一次，达到固定人数前不能准备或移动 LC 资金。
_Avoid_: Service Identity 授权、FTB 等级、重复点击计数、口头批准

**Withdrawal Approval Cancellation（提现审批取消）**:
在任何 Withdrawal Operation 出现前，由当前获授权 Citizen 终止一个 `PENDING` 或 `APPROVED` Withdrawal Approval 的不可变决定；它不移动 LC、不退款、不释放资金，也不补偿已有财政效果。
_Avoid_: Treasury Withdrawal cancellation、refund、Reservation release、Operation compensation

**首都（Capital）**:
国家唯一的领土连续性与财政治理锚点；只有 Nation Activation 才能建立首个首都。
_Avoid_: 出生点、任意已占领区块、FTB 团队基地

## 财政与账户

**国家国库（National Treasury）**:
由国家治理权限控制、可用于正式财政支出的 LC 银行资金账户。
_Avoid_: 国家财富、GDP、中央国库

**Treasury Withdrawal（国库提现）**:
把 National Treasury 的 LC 银行余额等额转换为交付给获授权 Citizen 的实体 LC 现金；它不改变 Cumulative Net Issuance，并在适用时引用一个已满足的 Withdrawal Approval。
_Avoid_: Payment、Issuance、Permanent Destruction、cash grant

**组织财政账户（Organization Fiscal Account）**:
由 Civic 权限控制、属于部门、协会、企业或承包组织的 LC 银行账户。
_Avoid_: LC 团队账户、玩家账户

**正式财政流程（Formal Fiscal Flow）**:
通过 Civic 预算、预留、托管或转账完成并产生完整审计记录的银行资金流程。
_Avoid_: 现金交易、个人赠款、普通银行转账

**服务身份（Service Identity）**:
由外部 Mod 或 Civic 内部系统登记、用于请求幂等、财政能力授权和审计归属的稳定调用主体；完成登记本身不授予写权限。
_Avoid_: 玩家身份、Mod 显示名称、任意请求字符串

**财政能力授权（Fiscal Capability Grant）**:
把一项明确财政能力授予一个服务身份，并严格限定到一个财政账户的可审计许可。
_Avoid_: 全局信任、余额、政府角色

**预留（Reservation）**:
在来源账户上锁定可用余额、等待结算或释放的财政承诺。
_Avoid_: 提现、发行、独立虚拟余额

**托管（Escrow）**:
以预留为资金保证、按照外部业务结果执行结算或退款的财政安排。
_Avoid_: 临时国库、奖励池

**Budget Disbursement Approval Policy（预算拨款审批策略）**:
一个 Nation 对已批准 Budget 的实际拨款按金额门槛规定所需不同 Citizen 审批人数的未来生效版本；拨款申请发起时命中的版本和人数会被固定。
_Avoid_: Budget approval、Withdrawal Approval Policy、全服固定双签

**Budget Disbursement Approval（预算拨款审批）**:
在任何 Payment 出现前，把一个已批准 Budget、精确收款账户、精确金额、发起人、理由和审批门槛固定为不可变财政决定；它本身不移动 LC，也不消耗 Reservation。
_Avoid_: Payment、Budget approval、Reservation settlement、口头拨款

**Budget Disbursement（预算拨款）**:
一个已完成 Budget Disbursement Approval 所授权的正式 Payment；它只能向审批固定的收款账户支付审批固定的金额，并消耗该 Budget 的剩余 Reservation。
_Avoid_: 未审批转账、Treasury Withdrawal、现金赠与、Budget cancellation

**公共维护基金（Public Maintenance Fund）**:
接收领土维护费非销毁部分、独立于所有国家并仅用于具体公共项目的全服基金。
_Avoid_: 世界国库、国家救助金

## 货币与发行

**累计净发行量（Cumulative Net Issuance）**:
Civic 已确认发行总额减去已确认永久销毁总额，是全服发行硬上限的核算口径。
_Avoid_: 当前现金存量、银行余额总和

**发行硬上限（Issuance Hard Cap）**:
服务器为累计净发行量设置、系统不能自动提高的绝对边界。
_Avoid_: 国家额度、国库余额

**国家发行额度（National Issuance Quota）**:
国家在指定周期内可以通过注册铸币实际发行的最高面值授权，不是余额或拨款。
_Avoid_: 免费资金、国家贷款、铸币产出

**组织铸币授权（Organization Mint Grant）**:
国家从自身额度中授予组织执行铸币生产的权限，产出仍归国家国库。
_Avoid_: 组织发行额度、生产补偿

**注册铸币机（Registered Mint）**:
持有永久身份、精确位置、国家与运营组织绑定、许可证、自动化许可、锁定配方版本和事务状态，只能按 Civic 配方与额度执行发行的生产设施。
_Avoid_: LC Coin Mint、普通加工机

**铸币配方版本（Mint Recipe Version）**:
全服统一且不可变的复合材料与加工时间规则；一个输入组可以声明精确物品、标签或替代材料，数量可以固定或按发行面值计算，已启动批次始终锁定原版本。
_Avoid_: 实时浮动配方、机器本地配方、LC Coin Mint 配方

**铸币批次（Mint Batch）**:
一次由注册铸币机执行的持久化发行状态机；准备阶段先锁定国家额度与配方材料清单，材料进入 Civic 托管后才开始加工，只有最终幂等提交才成为实际发行。
_Avoid_: 机器计时器、直接余额增加、可重试的普通加工任务

**永久销毁（Permanent Destruction）**:
由 Civic 明确确认、使相应金额退出累计净发行量的货币处理。
_Avoid_: 转入国库、封存、未知现金丢失

**铸币恢复事件（Mint Recovery Incident）**:
关联一个铸币批次持久化发行步骤、表明其外部结果尚未被 Civic 可靠确认的不可删除证据记录；事件被解决后仍保留同一历史身份。
_Avoid_: 重试日志、已取消批次、已释放额度

**Mint Compliance Observation（铸币合规观察）**:
一个 Nation 在合规滚动窗口内由单个 Mint Batch 形成的一次履约结果；同一批次的重试和多个恢复步骤不能重复形成合规样本。
_Avoid_: 恢复日志条目、每次重试、管理员处罚分

**Quarantined Mint Compliance Observation（隔离铸币合规观察）**:
恢复事故虽已解决但对应 Mint Batch 尚未完成最终提交的一次合规观察；它不能被视为成功恢复或从合规证据中消失。
_Avoid_: Recovered Commit、已清除事故、无样本

**货币存量校正（Monetary Stock Correction）**:
在独立证据确认真实 LC 货币与 Civic 累计净发行记录存在偏差后，由服务器级权限执行的审计调整。
_Avoid_: 普通支付、退款、额度授予、铸币批次重试

## 领土与实力

**免费领土额度（Territory Free Allocation）**:
国家在一个维护周期内无需支付维护费的区块数量，由服务器基础额度与 Effective Citizen 数共同形成；它不是货币、退款或永久领土权。
_Avoid_: 免费领土所有权、维护退款、FTB 成员额度

**领土占领许可（Territory Claim Permit）**:
针对一个 Nation、操作者和目标区块的单次占领授权，证明所需财政预付已经确认；它可被占领事件消费，但本身不是 FTB 所有权、余额或 Reservation。
_Avoid_: 预付余额、永久占领权、通用领土权限

**免费占领授权（Free Claim Authorization）**:
针对一个 Nation、操作者和目标区块的短时单次授权，证明该次新增领土在服务器计算时仍处于 Territory Free Allocation 内；它不证明付款、不产生可退款金额，也不是 Territory Claim Permit。
_Avoid_: 零金额 Permit、免费领土所有权、财政预付

**有效领土（Effective Territory）**:
由 FTB Chunks 归属某国且当前满足 Civic 财政维护条件的区块。
_Avoid_: 所有已占领区块、国界声明

**领土维护周期（Territory Maintenance Cycle）**:
全服统一、互不重叠的领土财政结算时间区间；区块的维护评估与财政有效性必须归属于一个明确周期。
_Avoid_: 实时时钟计费、单个国家周期、FTB 占领周期

**领土财政评估（Territory Fiscal Assessment）**:
在一个 Territory Maintenance Cycle 内，对 FTB 当前归属区块记录的待结算维护金额事实；它在结算前保持 PENDING，不复制或改变 FTB 所有权。
_Avoid_: FTB 所有权、付款交易、有效性结论

**领土维护结算（Territory Maintenance Settlement）**:
对一个 Nation 在一个 Territory Maintenance Cycle 内全部 Territory Fiscal Assessment 作出的单次财政结论；结算结果为 FULLY_FUNDED、PARTIALLY_FUNDED 或 UNFUNDED，并将每条评估分别结论为 EFFECTIVE 或 SUSPENDED。正费用续费只能由服务端读取精确 National Treasury 可用余额并按 Territory Maintenance Priority 选择；零费用评估可以在没有虚构财政交易的情况下结论为 EFFECTIVE。
_Avoid_: 单区块付款、FTB 所有权变更、调用方声明的成功

**领土强制加载执行（Territory Force-load Enforcement）**:
一条 Territory Fiscal Assessment 结论为 SUSPENDED 并经过固定 24 小时短宽限后，Civic 对该评估持久化的精确 FTB Team、维度和区块执行的服务端强制加载关闭；它只取消 force-load，不删除、转移或重新声明底层 FTB Claim，并以 PREPARED、EXTERNAL_APPLIED、CIVIC_COMMITTED 三阶段恢复到精确一次的 Civic 结论。
_Avoid_: 取消领土、调用方提供目标、批量关闭其他 Team 区块

**领土强制加载限制（Territory Force-load Restriction）**:
固定 24 小时短宽限结束后，由一个精确 FTB Team、维度和区块的最新已结论 Territory Fiscal Assessment 派生的服务端限制；最新结论为 SUSPENDED 时阻止新的 FTB force-load 请求，较新的 EFFECTIVE 结论解除该限制，但 Civic 不自动重新启用外部 force-load 状态。运行时只读取异步刷新的权威镜像，不在 FTB 事件线程同步查询 SQLite。
_Avoid_: 全服 force-load 开关、调用方声明的财政状态、恢复后自动 force-load

**领土恢复（Territory Maintenance Restoration）**:
先前因维护不足而 SUSPENDED 的精确区块重新取得财政有效性的独立、持久化结论；它引用但不改写来源 Territory Fiscal Assessment，只收配置的恢复费和下一个完整周期维护预付，不追缴历史欠费，并在成功恢复后进入冷却。每条后续评估明确记录恢复资格：无需恢复（NOT_REQUIRED）、可恢复（ELIGIBLE）或冷却阻止（COOLDOWN_BLOCKED）；冷却阻止的区块不能因余额充足而重新生效。
_Avoid_: 改写旧 Assessment、补缴全部欠费、重新占领 FTB 区块、无冷却反复恢复

**领土恢复预付抵扣（Territory Restoration Prepayment Credit）**:
一次已提交 Territory Maintenance Restoration 为一个精确 Nation、FTB Team 和区块支付的下一个完整周期维护金额；它只能抵扣该目标后续维护 Assessment，不能提取为 LC、转移给其他区块或被同一 Assessment 重复消费。
_Avoid_: 虚拟余额、现金退款、全国家通用抵扣、调用方声明的已付款

**领土维护优先级（Territory Maintenance Priority）**:
国家在维护资金不足时用于选择续费区块的五级顺序：首都、首都连接核心、有效基础设施、普通领土、飞地或跨维度领地；低级别不能挤占高级别。
_Avoid_: FTB 占领权限、区块遍历顺序、部分付款比例

**领土维护政策（Territory Maintenance Policy）**:
由受信任管理员延迟生效并公开审计的全服周期、维护费、飞地或跨维度倍率、强制加载附加费、恢复费、恢复冷却和销毁比例规则；未配置时不能自动产生维护结论。
_Avoid_: 单国费率、硬编码默认费率、自动税率

**领土维护抵扣额（Territory Maintenance Credit）**:
主动放弃领土后形成、只能抵扣后续领土维护而不能提取为货币的财政权益。
_Avoid_: 退款、账户余额

**财政交通连接（Fiscal Transport Link）**:
由持续维护的交通设施证明、可以降低飞地或跨维度领地附加成本但不能消除它的连接关系。
_Avoid_: 区块连续、外交通行权

**国家实力（National Strength）**:
由有效公民、生产、可审计经济、有效领土和合规共同形成的可持续能力指标。
_Avoid_: 国库余额、军事实力、GDP

**可审计经济活动证据（Auditable Economic Activity Evidence）**:
将一笔精确、已提交且未退款的正式财政转移绑定到经营实体、登记设施或公共项目，并提供足以检查交易对手、控制关系和参考价值的服务器权威记录。
_Avoid_: 总账条目、普通银行转账、现金收据、管理员调整

**登记设施（Registered Facility）**:
在有效领土内具有唯一范围和核算边界、可以产生经验证生产或服务记录的固定设施。
_Avoid_: 任意机器集合、工厂建筑外观

**设施核算基线（Facility Accounting Baseline）**:
一个 Registered Facility 在首次登记或范围变化后、开始接受生产证据前建立的固定机器、核算接口和库存起点；基线尚未完成时设施不能贡献国力。
_Avoid_: 当前库存价值、Create 动力容量、自动生产分

**设施核算接口（Facility Accounting Interface）**:
一个 Registered Facility 唯一绑定、位置固定的 Civic 专用库存核算边界，用于确认产物进入该设施的可审计库存路径。
_Avoid_: 普通箱子、任意物流网、设施核心

**设施核算入库记录（Facility Accounting Receipt）**:
Facility Accounting Interface 对一次精确产物入库形成的服务器权威记录；它必须能与受支持机器的真实配方完成对应，但对应成功本身不保证形成国力贡献。
_Avoid_: 玩家收据、库存快照、机器完成次数

**生产净增值（Production Value Added）**:
受验证产出的全服参考价值减去已消耗原料参考价值后的生产贡献。
_Avoid_: 产出总价、机器次数、库存总值

**生产产业类别（Production Industry）**:
由可信生产政策按精确的受支持配方身份与兼容版本赋予生产贡献的稳定产业归类，用于对同类生产执行统一边际递减；归类在证据发生时固定，不能按整个设施、物品名称、时间邻近或玩家声明临时猜测。
_Avoid_: 设施行业、配方次数、物品标签猜测、玩家自报行业

**生产边际回报（Production Marginal Return）**:
生产净增值在单设施和单产业软上限之后保留的国力贡献；软上限不否定真实生产，只降低超额部分的贡献权重。
_Avoid_: 硬性禁止生产、税款、货币销毁、产量清零

**生产边际回报策略（Production Marginal Return Policy）**:
由受信任服务器治理安排、在未来时刻生效的单设施与单产业软上限及超额权重规则；新版本不能改写旧生产证据或旧评估采用的策略。
_Avoid_: 即时配置、自动税率、产量限制、玩家自定义倍率

**生产国力政策（Production Strength Policy）**:
由受信任服务器治理安排、在未来时刻生效的全服生产观察窗口、满权重区间和国力满分尺度规则；它按 National Strength 评估时刻解释已绑定证据，不改写证据时刻的价格、Production Industry 或 Production Marginal Return Policy。
_Avoid_: Production Marginal Return Policy、硬编码生产窗口、证据时刻策略

**有效公民国力政策（Effective Citizen Strength Policy）**:
由受信任服务器治理安排、在未来时刻生效的全服 Effective Citizen 国力满分尺度；它按 National Strength 评估时刻解释人口当量，不改写 Citizenship、在线区间或历史人口证据。
_Avoid_: 建国人数门槛、FTB Team 成员上限、硬编码人口满分值

**有效领土国力政策（Effective Territory Strength Policy）**:
由受信任服务器治理安排、在未来时刻生效的全服 Effective Territory 国力满分尺度；它按 National Strength 评估时刻解释 Effective Claim 数，不改写 FTB Claim、Territory Fiscal Assessment、Settlement、Restoration 或历史领土证据。

**铸币合规政策（Mint Compliance Policy）**:
由受信任服务器治理安排、在未来时刻生效的全服铸币合规解释规则；它规定评估时使用的观察窗口和 `RECOVERED_COMMIT` 权重，只决定哪些既有 Mint Compliance Observation 进入当前 National Strength 快照及其分值，不改写 Mint Batch、Mint Recovery Incident、Monetary Stock Correction 或历史观察证据。

**可审计经济活动政策（Auditable Economic Activity Policy）**:
由受信任服务器治理安排、在未来时刻生效的全服正式经济活动解释规则；它规定 National Strength 评估使用的观察窗口和满分所需 LC 小单位规模，只解释既有 Auditable Economic Activity Evidence，不改写 Payment、Refund、General Ledger 或历史分类证据。

**注册设施范围政策（Registered Facility Scope Policy）**:
由受信任服务器治理安排、在未来时刻生效的全服 Registered Facility 最大连续 Claim 数；设施注册及后续权威管理在操作时读取当前版本，无政策时失败关闭，后续版本不扩张、裁剪或改写既有 Facility Scope。
_Avoid_: 免费领土额度、领土维护政策、硬编码领土满分值

**滚动生产边际回报评估（Rolling Production Marginal Return Assessment）**:
一个 Nation 在统一滚动窗口内，把证据时刻绑定的生产贡献按近期权重、单设施和单产业累计用量得出的可解释结论；策略换版不会重置同一窗口内已经使用的软上限。
_Avoid_: 策略版本分桶、永久生产总分、当前策略重算、设施计数

**证据时刻生产贡献绑定（Evidence-Time Production Contribution Binding）**:
把一条出口锚定的正 Production Value Added 固定到证据发生时有效的 Registered Facility、Production Industry assignment 和 Production Marginal Return Policy；拆分出口与后续策略版本不能复制或改写这条贡献。
_Avoid_: 当前策略重算、每次出口贡献、可变生产评分、玩家声明的策略

**全服参考价（Global Reference Price）**:
由初始配置与可信交易样本共同形成、供所有国家使用的统一核算价格。
_Avoid_: 本地售价、管理员随意估价

**库存年龄批次（Production Inventory Age Batch）**:
一次 Facility Accounting Receipt 中同一槽位与物品组件身份的进入数量，以及该数量首次进入核算接口的时间；接口内部搬运不刷新时间，真实消费按最早批次优先扣减。
_Avoid_: 当前库存快照、搬运时间、库存总值

**可信生产库存出口边界（Trusted Production Inventory Export Boundary）**:
由服务器确认的设施核算接口出口事件；请求只表达槽位、数量和出口意图，实际物品与组件身份必须来自服务器真实移出的 ItemStack，不能由调用者提交字符串伪造。
_Avoid_: 普通箱子搬运、客户端销售声明、任意物流网络扫描

**生产库存出口记录（Production Inventory Export Event）**:
一次可信出口与对应库存消费的不可变、可重放审计记录，绑定真实玩家、核算接口、出口类型和服务器记录的物品身份；SALE、EXPORT 与 PUBLIC_WORKS 只表示出口边界，不自动创造货币。
_Avoid_: 玩家收据、库存估值、自动付款

**出口锚定生产链证据（Export-Anchored Production Chain Evidence）**:
以可信生产库存出口记录的实际消费来源 Receipt 作为生产链证据来源；同一个来源 Receipt 被拆成多次出口时只形成一次生产贡献，缺少来源 Receipt 时不形成贡献。
_Avoid_: 出口次数、机器次数、未绑定库存快照、自动推测跨设施运输

**跨设施出口交接（Cross-Facility Export Handoff）**:
由服务器明确确认的一次 `EXPORT` 与另一座 Registered Facility 的 Facility Accounting Receipt 之间的运输事实；交接必须指向真实目标核算接口和能覆盖该批货物的真实后续 Receipt，不能由物品身份或时间邻近自动推断。
_Avoid_: 同设施搬运、SALE 交接、客户端提交的目标字符串、相似物品猜测
