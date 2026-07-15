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

**国籍协调（Citizenship Reconciliation）**:
把正式 Citizenship 与绑定 FTB Team 的当前成员事实进行服务端校验的过程；它可以启动或解除国籍纠错宽限，但不会把新 Team 成员自动登记为 Citizen。
_Avoid_: FTB 成员同步、自动入籍、团队即国家

**国家财政角色（Nation Fiscal Role）**:
一个 Citizen 在单一 Nation 内当前持有的精确财政权限集合；它不继承 FTB 等级，并在该 Citizen 不再具有有效国家归属时停止生效。
_Avoid_: FTB 等级、全局 OP、服务身份

**国家财政权限（Nation Fiscal Permission）**:
国家治理授予某个 Citizen 的单一、明确财政动作权限，例如批准预算、管理提现或管理领土财政。
_Avoid_: 全局财政访问、模糊官职、账户余额

**首都（Capital）**:
国家唯一的领土连续性与财政治理锚点；只有 Nation Activation 才能建立首个首都。
_Avoid_: 出生点、任意已占领区块、FTB 团队基地

## 财政与账户

**国家国库（National Treasury）**:
由国家治理权限控制、可用于正式财政支出的 LC 银行资金账户。
_Avoid_: 国家财富、GDP、中央国库

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
持有国家绑定、许可证和稳定身份，只能按 Civic 配方与额度执行发行的生产设施。
_Avoid_: LC Coin Mint、普通加工机

**永久销毁（Permanent Destruction）**:
由 Civic 明确确认、使相应金额退出累计净发行量的货币处理。
_Avoid_: 转入国库、封存、未知现金丢失

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

**领土恢复（Territory Maintenance Restoration）**:
先前因维护不足而 SUSPENDED 的区块重新取得财政有效性的过程；只收配置的恢复费和下一个完整周期维护费，不追缴历史欠费，并在成功恢复后进入冷却。每条后续评估明确记录恢复资格：无需恢复（NOT_REQUIRED）、可恢复（ELIGIBLE）或冷却阻止（COOLDOWN_BLOCKED）；冷却阻止的区块不能因余额充足而重新生效。
_Avoid_: 补缴全部欠费、重新占领 FTB 区块、无冷却反复恢复

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

**登记设施（Registered Facility）**:
在有效领土内具有唯一范围和核算边界、可以产生经验证生产或服务记录的固定设施。
_Avoid_: 任意机器集合、工厂建筑外观

**生产净增值（Production Value Added）**:
受验证产出的全服参考价值减去已消耗原料参考价值后的生产贡献。
_Avoid_: 产出总价、机器次数、库存总值

**全服参考价（Global Reference Price）**:
由初始配置与可信交易样本共同形成、供所有国家使用的统一核算价格。
_Avoid_: 本地售价、管理员随意估价
