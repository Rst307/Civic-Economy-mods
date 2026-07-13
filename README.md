# Civic Economy

Civic Economy 是面向 Minecraft 1.21.1 NeoForge 多国家服务器的经济与国家财政基础设施。

项目采用一套全服统一的 Lightman’s Currency 主货币；每个国家拥有独立国库，全服统一控制累计净发行量。国家系统负责国家身份与政治规则，Civic Economy 负责财政、支付、发行、领土维护和可审计经济指标。

## 当前状态

项目处于**规格完成、实现尚未开始**阶段。不要直接从完整业务功能开工，第一开发阶段是依赖技术探针和工程骨架。

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

## 第一开发里程碑

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

## 给另一个 AI 的起始提示

> 请先完整阅读 `README.md`、`AGENTS.md`、v1.1 主规格、`CONTEXT.md` 和全部 ADR。严格只完成第一阶段 NeoForge 工程骨架、依赖版本探针与测试，不实现业务功能。所有集成结论必须来自实际源码、构建或运行证据；完成后提交代码、验证记录和下一阶段接口建议。
