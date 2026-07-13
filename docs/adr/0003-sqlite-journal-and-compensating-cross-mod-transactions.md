# SQLite 日志与跨模组补偿事务

Civic 使用 SQLite WAL 保存财政写模型和事务状态，并通过外部幂等标识与补偿流程协调 LC 和 FTB Chunks。LC、FTB Chunks 与 Civic 不共享数据库，因此不能承诺真正的共同原子提交；持久化状态机能够在崩溃后继续提交或补偿，并向玩家提供唯一、可解释的最终结果。
