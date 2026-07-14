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

## 当前游戏内命令

玩家建国流程由服务端从真实玩家、FTB Team 和当前位置推导身份，不接受调用者提交 Team UUID、所有者或首都坐标：

```text
/civic economy nation apply
/civic economy nation status
/civic economy nation cancel <reason>
/civic economy nation activate
```

激活时玩家必须是绑定 FTB Team 的负责人，当前位置所在区块必须已由同一 Team 在 FTB Chunks 中占领。正式世界必须满足有效候选人门槛；只有永久标记的 `DEBUG WORLD` 才能使用单人绕过。

受信任 OP/控制台服务管理入口位于：

```text
/civic economy admin service list
/civic economy admin service show <service>
/civic economy admin service register ...
/civic economy admin service grant ...
/civic economy admin service revoke ...
/civic economy admin service disable ...
/civic economy admin service enable ...
```

单人集成服务器中，拥有作弊权限的世界所有者使用以下二次确认流程永久启用调试世界：

```text
/civic debug status
/civic debug enable
/civic debug enable confirm
```

专用服务器默认不注册调试写命令。只有在 JVM 启动参数显式加入 `-Dciviceconomy.allowDedicatedDebugWorld=true` 时才注册，并且仍只允许玩家 OP 操作；普通配置文件不能启用该权限。`DEBUG WORLD` 数据不得转成正式经济数据。

## 给另一个 AI 的起始提示

> 请先完整阅读 `README.md`、`AGENTS.md`、v1.1 主规格、`CONTEXT.md` 和全部 ADR。严格只完成第一阶段 NeoForge 工程骨架、依赖版本探针与测试，不实现业务功能。所有集成结论必须来自实际源码、构建或运行证据；完成后提交代码、验证记录和下一阶段接口建议。
