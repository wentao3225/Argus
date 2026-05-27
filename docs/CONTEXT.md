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
- 基于 [docs/auth/认证模块整体流程与代码定位.md](docs/auth/认证模块整体流程与代码定位.md) 讨论了两个需要修正的点：
	- 13.1 登录标识应改为不区分大小写。
- 进一步确认了 DevAdminInitializer 的现状：
	- 当前源码目录 [Argus-backend/src/main/java/com/argus/rag/auth/config](Argus-backend/src/main/java/com/argus/rag/auth/config) 中已经没有这个类。
	- 它不是认证主链路依赖，删除不会影响 login / register / refresh / change-password 主流程，只会失去 dev 环境自动初始化管理员账号的便利。
- 进一步确认了 upload 的前后端现状差异：
	- 后端分片上传链路是完整的，核心在 [Argus-backend/src/main/java/com/argus/rag/document/service/DocumentUploadService.java](Argus-backend/src/main/java/com/argus/rag/document/service/DocumentUploadService.java) 和 upload session / chunk 相关 mapper。
	- 前端 API 层已经定义了 init / chunk / complete 三段式接口，见 [Argus-frontend/src/api/document.ts](Argus-frontend/src/api/document.ts)。
- 已完成 ingestion 相关一轮轻量代码收口：
	- 当前实现中已移除 `ingestion_jobs` 相关表/实体/mapper/枚举，统一收口到 Spring 事件驱动的异步摄入链路。
	- `DocumentParserFactory` 已从较重的注册表思路收口为“简单工厂 + 策略模式”：`DocumentParser` 作为策略接口，各类 parser 作为具体策略实现，工厂基于扩展名选择策略。
	- 已同步更新当前态文档，避免源码和模块文档口径继续偏差。

## 4. 当前已验证的代码状态
- parser 层当前实现已经调整为：
	- `DocumentParser` 是解析策略接口。
	- `Txt / Md / Pdf / DocxDocumentParser` 是具体策略实现。
- 后端已执行 `mvn -q -DskipTests compile` 编译验证通过；parser 相关改动文件无 IDE 诊断错误。

## 5. 当前阶段结论
- parser 这一层当前最稳妥的讲法是：“简单工厂 + 策略模式按扩展名选择解析器”，不要再沿用“扩展名注册表”或“自动发现式注册中心”那套口径。

## 6. 下一步优先级
- 当前 ingestion 阅读时，需要按最新代码口径理解：不再有 `ingestion_jobs` 分支；parser 选择逻辑是简单工厂 + 策略，不是扩展名映射注册表。

## 7. 新对话接手建议
- 若后续继续看文档，优先以当前态模块文档为准；带 `V1.0`、`V2.0` 的文档默认视为历史阶段记录，不直接作为当前实现口径。
## 4. 当前已验证的代码状态

- 登录、注册、改密、上传主链路已系统梳理，auth/upload 相关主流程和能力边界已明确。
- ingestion 相关已完成 ingestion_jobs 相关代码和 schema 的彻底移除，主链路统一为 Spring 事件驱动异步摄入。
- parser 层已收口为“简单工厂 + 策略模式”，各 parser 只需实现 supports/parse，工厂按扩展名选择。
- ingestion 主链路已梳理到切片（StructureAwareChunkTransformer）核心源码，已完整理解其分层切片、降级、合并、overlap、元数据写入等主逻辑。
- 切片参数（target/max/overlap tokens）已确认当前 dev 环境实际值为 240/320/32，且为字符近似 token。
- 切片专题文档已补充到 docs/ingestion/文档切片实现详解.md，内容覆盖主链路、算法步骤、配置、边界、示例。
- 相关代码和文档均无 IDE 诊断错误，mvn 编译通过。

## 5. 当前阶段结论

- auth、upload 阶段已收口，主讲点和能力边界清晰。
- ingestion 目前主链路为：上传事务后事件驱动、异步 ETL、状态化失败收口、chunk 资产化、向量与关键词索引同步。
- parser 采用简单工厂 + 策略模式，切片采用结构感知分层切片，主链路和表达口径已与源码和文档同步。
- 切片方案优先保留文档结构，逐级降级，合并碎片，带 overlap 和元数据，适合当前项目工程目标。

## 6. 下一步优先级

- ingestion 已梳理到切片主逻辑，下一步建议继续看 ChunkService（切片落库）、vector/索引写入、READY/FAILED 状态回写。
- ingestion 梳理完成后，优先看 retrieval，串联“文档如何进入知识库”与“问答如何取回知识”。
- 阅读和表达时，优先以当前态源码和 docs/ingestion/文档切片实现详解.md 为准。

## 7. 新对话接手建议

- 默认从 ingestion async service / processor / chunk / vector 相关代码继续，不必回到 auth/upload 细节。
- 输出优先主链路、关键类定位、可讲工程点。
- ingestion 相关表达以“事件驱动异步链路、结构感知切片、资产化落库”为主。
- 参考 docs/ingestion/文档切片实现详解.md 作为切片讲解依据。

