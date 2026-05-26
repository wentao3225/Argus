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
- 已经系统过了一轮 document upload 主链路，确认后端能力边界覆盖：初始化上传会话、秒传复用、断点续传、分片上传、分片合并、文档落库、事务后异步触发 ingestion。
- 进一步确认了 upload 的前后端现状差异：
	- 后端分片上传链路是完整的，核心在 [Argus-backend/src/main/java/com/argus/rag/document/service/DocumentUploadService.java](Argus-backend/src/main/java/com/argus/rag/document/service/DocumentUploadService.java) 和 upload session / chunk 相关 mapper。
	- 前端 API 层已经定义了 init / chunk / complete 三段式接口，见 [Argus-frontend/src/api/document.ts](Argus-frontend/src/api/document.ts)。
	- 但当前页面主入口 [Argus-frontend/src/views/documents/components/UploadDialog.vue](Argus-frontend/src/views/documents/components/UploadDialog.vue) 实际接入的仍是 10MB 内直接上传，尚未看到真正把 File 切片并调分片接口的页面逻辑。

## 4. 当前已验证的代码状态

- 登录查询已经按大小写不敏感匹配处理，见 [Argus-backend/src/main/resources/mappers/UserMapper.xml](Argus-backend/src/main/resources/mappers/UserMapper.xml)。
- 注册唯一性校验也已经按大小写不敏感处理，见 [Argus-backend/src/main/java/com/argus/rag/auth/service/AuthService.java](Argus-backend/src/main/java/com/argus/rag/auth/service/AuthService.java)。
- 前端改密规则已抽成共享工具，见 [Argus-frontend/src/utils/password-policy.ts](Argus-frontend/src/utils/password-policy.ts)。
- 两个前端改密入口都已经接入统一校验与统一提示，见 [Argus-frontend/src/components/AccountPasswordForm.vue](Argus-frontend/src/components/AccountPasswordForm.vue) 和 [Argus-frontend/src/views/settings/SettingsView.vue](Argus-frontend/src/views/settings/SettingsView.vue)。
- 前端当前密码规则为：至少 8 位、同时包含字母和数字、最长 256 位、UTF-8 长度不超过 72 字节，目标是与后端策略保持一致。
- upload 初始化的三层判断已经确认：同群组同哈希 READY 文档直接秒传复用；同用户同哈希未过期会话走断点续传；否则新建上传会话。
- 分片进度不是靠前端自己缓存，而是由后端通过 `document_upload_sessions` 和 `document_upload_chunks` 两张表恢复；chunk 元数据写入使用 upsert，重复上传同一分片是幂等的。
- 上传完成后 documents 表里的文档先进入 PROCESSING，不会直接变成 READY；READY 由异步 ingestion / ETL 完成后统一回写。
- 异步衔接方式已经确认：上传事务提交后发布 [Argus-backend/src/main/java/com/argus/rag/document/service/DocumentIngestionRequestedEvent.java](Argus-backend/src/main/java/com/argus/rag/document/service/DocumentIngestionRequestedEvent.java)，由 [Argus-backend/src/main/java/com/argus/rag/document/service/DocumentIngestionAsyncListener.java](Argus-backend/src/main/java/com/argus/rag/document/service/DocumentIngestionAsyncListener.java) 在 AFTER_COMMIT 阶段异步启动处理。
- 当前还确认了一个实现细节：启动恢复组件 [Argus-backend/src/main/java/com/argus/rag/document/service/StaleProcessingDocumentRecoveryRunner.java](Argus-backend/src/main/java/com/argus/rag/document/service/StaleProcessingDocumentRecoveryRunner.java) 虽然注入了 processing-timeout-minutes 配置，但当前调用 SQL 时没有传入超时边界，现状更像“启动时回收所有遗留 PROCESSING 文档”。

## 5. 当前阶段结论

- auth 模块可以先收住，不必继续深挖边角实现。
- 对面试更值得保留的 auth 表达是：短期 access token、长期 refresh cookie、refresh token rotation、当前用户回库确认用户状态。
- 对 auth 里可以弱化的点是：dev admin 自举、过细的页面跳转分支、过重的“企业级安全平台”叙事。
- upload 模块现阶段也可以先收住，已经达到“主链路能讲、关键类能定位、工程价值能解释”的程度。
- 对 upload 最值得保留的表达是：会话化上传、秒传复用、断点续传、分片幂等、上传与 ETL 解耦、失败后可重试。
- 对 upload 里需要收口的点是：不要把当前项目讲成前端已经完整交付了大文件分片产品体验；更准确的说法是后端能力完整、前端页面主入口当前主要还是直传。

## 6. 下一步优先级

- 直接进入 ingestion / ETL 主链路源码梳理。
- 重点搞清：对象存储原文如何读取、不同文件格式如何解析、文本清洗和 preview_text 如何生成、chunk 如何切分并落库、向量和关键词索引如何写入、READY / FAILED 状态如何回写。
- 这一阶段的目标是把“为什么上传完成后还要经历 PROCESSING”讲清楚，也就是把 upload 的后半段真正补齐。
- ingestion 梳理完成后，下一跳优先看 retrieval，这样就能把“文档如何进入知识库”和“问答如何取回知识”连成一条完整主链路。

## 7. 新对话接手建议

- 默认从 ingestion async service / processor / parser / chunk / vector 相关代码开始，不要重新花太多时间回到 auth 或 upload 概念介绍。
- 输出优先给：主链路、关键类定位、适合写进简历的工程点、需要收口的表述。
- 不要默认 Elasticsearch 必须保留；后续如果需要轻量化，可以保留“混合检索”思路，但替换为更轻的关键词检索实现。
