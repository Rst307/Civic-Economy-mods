# 用稳定 NationId 隔离国家系统适配

Civic 生成永久 `NationId`，FTB Team UUID 和未来正式国家系统 ID 只通过 `NationProvider` 映射，不直接成为财政主键。虽然直接使用团队 UUID 更简单，但团队重建或更换国家模组会切断国库、领土和账本历史；稳定身份允许替换适配器而不迁移财政语义。
