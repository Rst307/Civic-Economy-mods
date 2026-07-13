# Civic Economy

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

**有效领土（Effective Territory）**:
由 FTB Chunks 归属某国且当前满足 Civic 财政维护条件的区块。
_Avoid_: 所有已占领区块、国界声明

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
