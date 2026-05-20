# Argus 会话上下文（2026-05-20）

## 1. 项目定位

- Argus 已确定替代 RAG-ONE，作为新的主项目。
- 当前目标不是再找新项目，而是在 2026-05-28 离职前先系统吃透模块和主链路；离职后再集中整理成项目描述、主讲稿、README 口径、面试追问与回答等成果物。
- 这个项目的讲法要收口成：一个更完整的 RAG 知识平台、一个有后端工程味道的 AI 项目、一个 1 年经验也能稳定讲清的主项目。
- 不要把 Argus 包装成完整商业化企业平台负责人项目，也不要只堆技术名词。

## 2. 协作原则

- 先基于真实源码和真实链路理解项目，再决定是否需要轻量改造。
- 优先服务于简历和面试表达，重点是“可复述、可追问、能落到代码”。
- 输出时优先给主链路、关键类定位、工程取舍和可讲亮点，不把精力放在无边界扩功能或大量补测试上。

## 3. 今天主要推进

- 对齐了整体背景和目标，确认 auth 已经系统过了一轮，下一阶段主攻 document upload，再往后是 ingestion / ETL、retrieval、assistant、group / permission。
- 基于 [docs/auth/认证模块整体流程与代码定位.md](docs/auth/认证模块整体流程与代码定位.md) 讨论了两个需要修正的点：
	- 13.1 登录标识应改为不区分大小写。
	- 13.2 前端改密前置校验应与后端密码策略保持一致。
- 进一步确认了 DevAdminInitializer 的现状：
	- 当前源码目录 [Argus-backend/src/main/java/com/argus/rag/auth/config](Argus-backend/src/main/java/com/argus/rag/auth/config) 中已经没有这个类。
	- 它不是认证主链路依赖，删除不会影响 login / register / refresh / change-password 主流程，只会失去 dev 环境自动初始化管理员账号的便利。
	- 目前仍有残留信息在 [Argus-backend/src/main/resources/application-dev.yml](Argus-backend/src/main/resources/application-dev.yml)、[docs/auth/认证模块整体流程与代码定位.md](docs/auth/认证模块整体流程与代码定位.md)、[docs/启动流程与配置加载说明.md](docs/启动流程与配置加载说明.md)，以及 target 目录旧编译产物中，后续可统一清理。

## 4. 当前已验证的代码状态

- 登录查询已经按大小写不敏感匹配处理，见 [Argus-backend/src/main/resources/mappers/UserMapper.xml](Argus-backend/src/main/resources/mappers/UserMapper.xml)。
- 注册唯一性校验也已经按大小写不敏感处理，见 [Argus-backend/src/main/java/com/argus/rag/auth/service/AuthService.java](Argus-backend/src/main/java/com/argus/rag/auth/service/AuthService.java)。
- 前端改密规则已抽成共享工具，见 [Argus-frontend/src/utils/password-policy.ts](Argus-frontend/src/utils/password-policy.ts)。
- 两个前端改密入口都已经接入统一校验与统一提示，见 [Argus-frontend/src/components/AccountPasswordForm.vue](Argus-frontend/src/components/AccountPasswordForm.vue) 和 [Argus-frontend/src/views/settings/SettingsView.vue](Argus-frontend/src/views/settings/SettingsView.vue)。
- 前端当前密码规则为：至少 8 位、同时包含字母和数字、最长 256 位、UTF-8 长度不超过 72 字节，目标是与后端策略保持一致。

## 5. 当前阶段结论

- auth 模块可以先收住，不必继续深挖边角实现。
- 对面试更值得保留的 auth 表达是：短期 access token、长期 refresh cookie、refresh token rotation、当前用户回库确认用户状态。
- 对 auth 里可以弱化的点是：dev admin 自举、过细的页面跳转分支、过重的“企业级安全平台”叙事。

## 6. 下一步优先级

- 直接进入 document upload 主链路源码梳理。
- 重点搞清：初始化上传会话、分片上传、断点续传、秒传 / 复用、合并完成、上传完成后如何衔接异步 ingestion / ETL。
- 目标不是立刻改架构，而是先把这条链路讲清楚，尤其要能说出它相比普通 RAG Demo 的工程化价值。

## 7. 新对话接手建议

- 默认从 upload 相关 controller / service / mapper 开始，不要重新花太多时间回到 auth 概念介绍。
- 输出优先给：主链路、关键类定位、适合写进简历的工程点、需要收口的表述。
- 不要默认 Elasticsearch 必须保留；后续如果需要轻量化，可以保留“混合检索”思路，但替换为更轻的关键词检索实现。
