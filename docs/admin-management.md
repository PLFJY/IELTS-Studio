# Admin Management

> 本文件说明 IELTS Studio 管理端用户管理的能力范围与安全规则。
> 配套阅读：[ai-provider-architecture.md](./ai-provider-architecture.md)、[security-and-quota-plan.md](./security-and-quota-plan.md)。

---

## 1. Current role model

当前系统沿用两级角色模型（不做复杂 RBAC）：

- `USER` — 普通用户
- `ADMIN` — 管理员

角色存储在 `users.role` 字段（`VARCHAR(20)`），由 Spring Security `hasRole("ADMIN")` 在路由层拦截 `/admin/**`，Controller 内 `requireAdmin(AuthUser)` 做防御性二次校验。

---

## 2. User management

Phase 8A 已落地的管理端用户管理能力（路径前缀 `/api/admin/users`）：

- **list / search users** — `GET /api/admin/users?page=1&pageSize=20&keyword=&role=&status=`
  - `keyword` 匹配 username / email
  - `role` 过滤：USER / ADMIN
  - `status` 过滤：ACTIVE（deleted=0）/ DISABLED（deleted=1）/ ALL
  - `page` 最小 1，`pageSize` 范围 1~100（越界自动 clamp）
- **get user detail** — `GET /api/admin/users/{id}`（包含已禁用用户）
- **update role** — `PUT /api/admin/users/{id}/role`（body: `{ "role": "ADMIN" }`）
- **disable user** — `PUT /api/admin/users/{id}/disable`（deleted=1）
- **enable user** — `PUT /api/admin/users/{id}/enable`（deleted=0，不改变 role）
- **reset password** — `POST /api/admin/users/{id}/reset-password`（body: `{ "newPassword": "..." }`）
- **create user** — `POST /api/admin/users`（body: `{ "username","email","password","role" }`，BCrypt 加密，role 白名单 USER/ADMIN）

### 逻辑删除处理

`users.deleted` 字段标注了 MyBatis-Plus `@TableLogic`，普通 wrapper 查询会自动过滤 `deleted=1` 的用户。
管理端需要能查到/操作已禁用用户，因此 `UserMapper` 扩展了自定义 SQL 方法
（`selectAdminUsers` / `countAdminUsers` / `selectUserIncludingDeleted` / `updateDeletedById` 等），
这些方法使用原生 `@Select` / `@Update` 注解，不受 `@TableLogic` 自动过滤影响，
**不破坏**现有普通用户查询语义（`findByUsername` / `findByEmail` / `selectById` 仍只返回启用用户）。

---

## 3. Safety rules

管理端操作必须遵守以下安全规则（后端 `AdminUserService` 强制校验，前端仅做提示）：

- **cannot disable yourself** — 不能禁用自己
- **cannot demote yourself** — 不能降级自己的管理员角色
- **cannot disable the last ADMIN** — 不能禁用最后一个启用的 ADMIN（`countActiveAdmins() <= 1` 时拒绝）
- **cannot demote the last ADMIN** — 不能降级最后一个启用的 ADMIN
- **password reset uses BCrypt** — 重置密码使用 `PasswordEncoder.encode(...)` 存储 BCrypt 哈希
- **password is never returned** — 所有 DTO（`AdminUserDto` / `AdminUserPageDto`）均不包含 `password` 字段
- **no plaintext password in logs** — `AdminResetPasswordRequest.newPassword` 通过 `@ToString.Exclude` 排除，service 只 log userId 不 log 密码
- **role whitelist** — `role` 只允许 `USER` / `ADMIN`，`SUPER_ADMIN` 等非法值在 service 层拒绝
- **userId / role from AuthUser only** — 当前操作者 ID 与角色只从 `@AuthenticationPrincipal AuthUser` 取，不信任前端传入

---

## 4. Future phases

- **Phase 8C**: permission hardening / optional RBAC — 可选的细粒度权限模型（如需引入 `roles` / `permissions` / `user_roles` 表）

---

## 5. Quota management

Phase 8B 支持当前周期 quota 管理（路径前缀 `/api/admin/quotas`）：

- **list quotas** — `GET /api/admin/quotas?page=1&pageSize=20&keyword=&role=&status=`
  - 筛选同用户管理：`keyword`（username/email）、`role`（USER/ADMIN）、`status`（ACTIVE/DISABLED/ALL）
  - 包含已禁用用户，复用 8A 的 `selectAdminUsers` / `countAdminUsers`
- **get user quota** — `GET /api/admin/quotas/users/{userId}`
- **set total** — `PUT /api/admin/quotas/users/{userId}/total`（body: `{ "creditsTotal": 100 }`）
- **grant credits** — `POST /api/admin/quotas/users/{userId}/grant`（body: `{ "credits": 20 }`）
- **reset used** — `POST /api/admin/quotas/users/{userId}/reset-used`

### 周期与默认值

- 周期计算与 `AiUsageGuard` / `AiUsageQueryService` 一致：周一 00:00:00 到下周一 00:00:00（服务器默认时区）。
- 默认每周 credits = 30（`DEFAULT_WEEKLY_CREDITS` 常量，与 `init.sql` 列默认值一致）。

### 无 quota 行时的处理

- **读操作**（list / get）：返回虚拟默认视图 `30/0/30`，`quotaRowExists=false`，**不创建** quota 行。
- **写操作**（set total / grant / reset used）：**创建**当前周期 quota 行后再写入：
  - set total：创建 `creditsTotal=request, creditsUsed=0`
  - grant：创建 `creditsTotal=30+credits, creditsUsed=0`
  - reset used：创建默认 `creditsTotal=30, creditsUsed=0`

### 写操作规则

- **set total**：`creditsTotal` 范围 0~100000；若 `creditsTotal < 当前 creditsUsed` 则拒绝；不修改 `creditsUsed`。
- **grant**：`credits` 范围 1~100000；`creditsTotal += credits`，不修改 `creditsUsed`。
- **reset used**：`creditsUsed = 0`，不修改 `creditsTotal`。

### 安全

- 所有返回的 `AdminQuotaDto` 不含 `password` / `apiKey` / `encryptedKey` / `maskedKey` / `baseUrl` / `model`。
- 不修改 AI Provider 调用链、扣费逻辑、rate limit、usage records；本阶段仅管理端手动调整 `ai_usage_quota` 表。
- 并发插入通过 `(user_id, period_start)` 唯一约束 + 失败重查兜底（与 `AiUsageGuard.getOrCreateCurrentQuota` 一致）。

