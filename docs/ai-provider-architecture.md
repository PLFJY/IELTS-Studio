# AI Provider 架构设计

> 本文件是后续 AI Provider 改造的**设计文档**，**只写设计，不实现代码**。
> 当前阶段（Phase 1）不重构现有 Java/Vue 代码，不改依赖配置。
> 配套阅读：[security-and-quota-plan.md](./security-and-quota-plan.md)、[database-change-guide.md](./database-change-guide.md)。
>
> **实现进度：**
> - ✅ Phase 2：Provider 抽象与客户端骨架已落地（`AiTaskType` / `AiKeyMode` / `AiProviderPreset` / `AiCredentials` / `AiProviderRegistry` / `AiSettingsService` BUILTIN / `AiUsageGuard` 骨架）。
> - ✅ Phase 3A：`user_ai_settings` / `ai_usage_quota` / `ai_usage_records` 三表 + Entity/Mapper + `AiApiKeyCrypto` 已落地。
> - ✅ Phase 3B：用户 AI 设置后端接口已落地（`UserAiSettingsService` + `UserAiSettingsController` + DTO），`AiSettingsService` 已支持 USER 模式凭据解析；现有 AI 业务调用链尚未迁移（留待 Phase 5）。
> - ✅ Phase 3B-polish：安全与一致性补强。DTO 不再使用 `@Data`，`apiKey` 字段通过 `@ToString.Exclude` 排除；`AiSettingsService.resolveUser` 在解密前校验 provider 是否支持当前 `taskType`；`UserAiSettingsService` 公共方法加 `requireUserId` 防御。
> - ✅ Phase 4：前端用户中心设置 UI 已落地（`ProfileView.vue` 增加 `AiSettingsCard` AI 设置区，masked key 展示）。
> - ✅ Phase 5A：现有**文本类** AI 功能接入新架构。已迁移 `AiParseService` 中的 `gradeWriting` / `translateWithContext` / `chatWithContext` 三个方法，统一走 `AiSettingsService.resolve` → `AiUsageGuard.checkBeforeCall` → `OpenAiCompatibleClient.chat` → `markSuccess`/`markFailure`；`/exams/grade-writing` 已改为需要登录。
> - ✅ Phase 5A-polish: adjusted usage accounting so success is recorded only after provider response is successfully parsed/validated (避免返回非法 JSON / 空内容时同时记成功与失败、失败仍被扣费)。
> - ✅ Phase 5A-local-provider-test: Phase 5A also includes local OpenAI-compatible provider tests using JDK HttpServer, covering request serialization, token field handling, sanitized errors, and text-service integration without calling real providers.
> - ✅ Phase 5B：继续迁移剩余 TEXT Provider 功能。已迁移 `ClozeService.generate` / `ClozeService.check` / `AiParseService.generateWordEntries` 三个方法走新架构（`AiSettingsService.resolve(userId, TEXT)` → `AiUsageGuard.checkBeforeCall` → `OpenAiCompatibleClient.chat` → JSON 解析成功后 `markSuccess`，失败 `markFailure`）；新增 `AiFeature.WORD_GENERATE`（TEXT, cost=2）；`ClozeService` 与 `WordBookController` 接口改为从 `AuthUser` 注入 `userId`，禁止前端传 `userId`；`AsyncWordService` 两个调用点已下传 `userId`；provider 错误统一脱敏返回通用提示。
> - ✅ Phase 5C-1：普通文本试卷解析（`parsePrecise=false` 链路）接入新 Provider 架构。已迁移 `AiParseService.parseWithAi(Long userId, String)` / `detectAndParseMultiSection(Long userId, String)` / `workflowStep1` / `workflowStep1A` / `workflowStep1B` / `workflowStep2` 六个方法走 `AiFeature.EXAM_PARSE`（TEXT, cost=5）+ 新架构；`ExamService.uploadExam` / `uploadExamImages` 从 `AuthUser` 注入 `userId` 并下传给 `AsyncParseService.parseAndSave` / `parseAndSaveImages`，再透传到 `parseSingle` / `workflowParse` / `handleMultiSection` / `commitSection`（含 0-question 时的 `parseWithAi` retry）；`AiParseService.isConfigured()` 改为始终返回 `true`，避免 USER 模式被误判为未配置；未迁移的 `generateWritingGuidance` / `extractHeadingsWithAi` 改用 `hasLegacyDeepSeekKey()` 判断旧 DeepSeek client 是否可用；移除无调用方的 5-arg `parseAndSave` legacy 重载；provider 错误统一脱敏。新增 `AiParseServiceExamParseProviderTest`（6 用例）与 `AsyncParseServiceUserIdPropagationTest`（1 用例）。
>   - 仍未迁移：Qwen / MiMO Vision 精准解析、`parsePrecise=true` 主链路、`QwenDocumentParseService`、Vision Provider local tests、Writing guidance workflow（仍走旧 DeepSeek HttpClient）。

---

## 1. 当前现状

| 任务类型 | 主要 Service | Provider | 说明 |
|---|---|---|---|
| 文本试卷解析 | `AiParseService` | DeepSeek | 普通 PDF / Word 文字结构化 |
| 写作评分 | `AiParseService` | DeepSeek | TR / CC / LR / GRA + Band 分 |
| AI 助手问答 | `AiParseService` | DeepSeek | 考试中上下文问答 |
| 划词翻译 | `AiParseService` | DeepSeek | 翻译 + 语法注释 |
| 完形填空生成/批改 | `ClozeService`（依赖 AI） | DeepSeek | 文本任务 |
| 多模态精准解析 | `QwenAiParseService` / `QwenDocumentParseService` | Qwen VL | PDF 逐页渲染为图片后视觉识别 |
| 备选解析 | `LlamaParseService` | LlamaParse | 备选方案 |

**问题：**

- AI Key 由站点统一配置（`application-ai.yml`），用户无法自带 Key。
- 文本与多模态调用散落在不同 Service，没有统一抽象。
- 没有用户级 quota 与 rate limit。
- 用户无法选择 Provider 或自定义 Base URL / Model。

---

## 2. 目标架构

### 2.1 任务类型枚举

```java
public enum AiTaskType {
    TEXT,   // 文本任务：写作评分、AI Chat、翻译、普通解析、完形填空
    VISION  // 多模态任务：PDF/图片精准解析、扫描版试卷、写作 Task 1 图表识别
}
```

### 2.2 Key 模式枚举

```java
public enum AiKeyMode {
    BUILTIN, // 使用站点内置 API Key，消耗站点 credits
    USER     // 使用用户自填 API Key，不消耗站点 credits
}
```

### 2.3 核心抽象

| 组件 | 职责 |
|---|---|
| `AiProviderPreset` | 预设 Provider 枚举/配置（DeepSeek、Qwen、MiMO 等），含默认 Base URL / Model |
| `AiCredentials` | 一次 AI 调用所需的凭据快照（provider、baseUrl、model、apiKey 明文-内存、keyMode），由 `AiSettingsService` 解密后注入 |
| `OpenAiCompatibleClient` | 统一 HTTP 客户端，封装 OpenAI-compatible `/chat/completions`，支持 text 与 vision（image_url）消息 |
| `AiSettingsService` | 读取用户 AI 设置（`user_ai_settings`），决定 Key 模式、解密 API Key、构造 `AiCredentials` |
| `AiUsageGuard` | 用量守卫：内置模式校验 credits 余量并扣费；自填模式做 rate limit / timeout / 输入长度校验 |

### 2.4 调用流程（设计）

```
Controller
   │  @AuthenticationPrincipal AuthUser
   ▼
SomeService
   │  AiTaskType.TEXT / VISION
   ▼
AiSettingsService.resolve(userId, taskType) → AiCredentials
   │  (BUILTIN: 站点 Key；USER: 解密用户 Key)
   ▼
AiUsageGuard.checkAndConsume(userId, taskType, keyMode, cost)
   │  (BUILTIN: 扣 credits；USER: rate limit)
   ▼
OpenAiCompatibleClient.chat(credentials, messages) → result
   │  (异常脱敏后抛出)
   ▼
SomeService 组装业务结果 → Result.success(...)
```

---

## 3. Provider 分类

### 3.1 Text Provider

| Provider | 说明 |
|---|---|
| DeepSeek | 默认文本 Provider，性价比高，写作评分/解析/翻译/Chat |
| OpenAI-compatible custom | 用户自填 Base URL / Model / API Key，走 `/chat/completions` |

### 3.2 Vision Provider

| Provider | 说明 |
|---|---|
| Qwen | 通义千问 VL，PDF/图片精准解析、扫描版试卷 |
| MiMO | 多模态解析备选 |
| OpenAI-compatible custom | 用户自填支持 vision 的 Base URL / Model / API Key |

> 约定：所有 Provider 尽量走 OpenAI-compatible `/chat/completions` 协议；多模态用 `image_url` 消息体。
> 非 OpenAI-compatible 的 Provider（如有）由 `OpenAiCompatibleClient` 适配层屏蔽差异，业务层不感知。

---

## 4. 用户设置模型

一个用户有一份 AI 设置（`user_ai_settings` 一对一）：

- **大开关**：`key_mode = BUILTIN | USER`
- **BUILTIN 模式**：无需配置 Provider，直接用站点内置 Key，消耗 credits
- **USER 模式**：分别配置文本任务和多模态任务
  - Text：`text_provider` + `text_base_url` + `text_model` + `text_api_key_encrypted` + `text_api_key_masked`
  - Vision：`vision_provider` + `vision_base_url` + `vision_model` + `vision_api_key_encrypted` + `vision_api_key_masked`

字段设计详见 [database-change-guide.md](./database-change-guide.md)。

---

## 5. 安全规则

- **API Key 加密存储**：`*_api_key_encrypted` 字段使用可逆加密（如 AES），密钥由后端持有，**不**入库。
- **前端只展示 masked key**：`*_api_key_masked`（如 `sk-****abcd`），**永不**返回明文。
- **日志禁止输出 key**：打印请求时去掉 `Authorization` 头与 body 中的 key；打印 provider 响应时只保留必要字段。
- **自定义 Base URL 校验**：
  - 必须 `http`/`https` 协议。
  - host 不允许指向内网/保留地址（防 SSRF），除非显式配置白名单。
  - 校验失败返回明确业务错误，不直接转发请求。
- **错误脱敏**：provider 原始异常不完整返回前端，后端记录脱敏后的日志。

详细安全策略见 [security-and-quota-plan.md](./security-and-quota-plan.md)。

---

## 6. 后续实施阶段

| 阶段 | 状态 | 内容 |
|---|---|---|
| Phase 1 | ✅ 已完成 | Agent 文档与开发基础设施（`AGENTS.md`、`docs/*`） |
| Phase 2 | ✅ 已完成 | Provider 抽象与 OpenAI-compatible client：`AiTaskType` / `AiKeyMode` / `AiProviderPreset` / `AiCredentials` / `AiProviderRegistry` / `AiSettingsService` BUILTIN / `AiUsageGuard` 骨架 |
| Phase 3A | ✅ 已完成 | 数据库表、Entity/Mapper、API Key 加密工具（`init.sql`、`entity/`、`mapper/`、`AiApiKeyCrypto`） |
| Phase 3B | ✅ 已完成 | 用户 AI 设置后端接口与 USER 模式 credentials 解析（`UserAiSettingsService` / `UserAiSettingsController` / DTO，`AiSettingsService` 已支持 USER 模式） |
| Phase 3B-polish | ✅ 已完成 | 安全补强：DTO `toString()` 防泄露、`resolveUser` 解密前校验 provider-taskType、`requireUserId` 防御 |
| Phase 4 | ✅ 已完成 | 前端用户中心 AI 设置 UI：在 `ProfileView.vue` 增加 AI 设置区（`AiSettingsCard.vue`），masked key 展示（`frontend/src/views/ProfileView.vue`、`frontend/src/components/profile/`、`frontend/src/api/aiSettings.js`） |
| Phase 5A | ✅ 已完成 | 现有**文本类** AI 功能接入新架构：迁移 `AiParseService.gradeWriting` / `translateWithContext` / `chatWithContext` 走 `AiSettingsService` + `AiUsageGuard` + `OpenAiCompatibleClient`；`/exams/grade-writing` 改为需要登录 |
| Phase 5B | ✅ 已完成 | 继续迁移 TEXT Provider 功能：`ClozeService.generate` / `ClozeService.check` / `AiParseService.generateWordEntries` 走新架构；新增 `AiFeature.WORD_GENERATE`；`/words/cloze/*` 接口从 `AuthUser` 注入 `userId`；`AsyncWordService` 调用点下传 `userId` |
| Phase 5C-1 | ✅ 已完成 | 普通文本试卷解析（`parsePrecise=false`）接入新架构：`AiParseService.parseWithAi` / `detectAndParseMultiSection` / `workflowStep1` / `workflowStep1A` / `workflowStep1B` / `workflowStep2` 走 `AiFeature.EXAM_PARSE`；`ExamService` → `AsyncParseService` 一路下传 `userId`（`parseAndSave` / `parseAndSaveImages` / `parseSingle` / `workflowParse` / `handleMultiSection` / `commitSection`）；`isConfigured()` 改为 `return true`；移除 5-arg legacy `parseAndSave` |
| Phase 5C-2 | 后续 | 其余 AI 功能接入新架构：PDF/图片精准解析（`parsePrecise=true` 主链路）、Qwen/MiMO 多模态、`QwenDocumentParseService`、Vision Provider local tests、Writing guidance workflow（`generateWritingGuidance` / `extractHeadingsWithAi` 仍走旧 DeepSeek HttpClient） |

> 各阶段应独立 PR，小步推进，每阶段都要跑通验证命令（`mvn test` / `npm run build`）。
