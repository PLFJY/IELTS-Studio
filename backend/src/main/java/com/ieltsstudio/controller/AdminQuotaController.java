package com.ieltsstudio.controller;

import com.ieltsstudio.common.Result;
import com.ieltsstudio.dto.admin.AdminGrantCreditsRequest;
import com.ieltsstudio.dto.admin.AdminQuotaDto;
import com.ieltsstudio.dto.admin.AdminQuotaPageDto;
import com.ieltsstudio.dto.admin.AdminSetQuotaTotalRequest;
import com.ieltsstudio.security.AuthUser;
import com.ieltsstudio.service.AdminQuotaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 AI 额度管理接口（Phase 8B）。
 *
 * <p>路径前缀：{@code /admin/quotas}（叠加 context-path {@code /api} 后为
 * {@code /api/admin/quotas}）。</p>
 *
 * <p><b>鉴权：</b>所有接口必须 ADMIN 角色访问。
 * <ul>
 *   <li>Spring Security 已对 {@code /admin/**} 配置 {@code hasRole("ADMIN")}。</li>
 *   <li>Controller 内 {@link #requireAdmin(AuthUser)} 做防御性二次校验，不信任前端传 role。</li>
 *   <li>当前管理员 id / role 只从 {@code @AuthenticationPrincipal AuthUser} 取。</li>
 * </ul>
 *
 * <p><b>安全：</b>返回的 DTO 不含 password / apiKey / encrypted / masked / baseUrl / model。
 * 不修改 AI Provider 调用链、扣费逻辑、rate limit、usage records。</p>
 */
@Slf4j
@RestController
@RequestMapping("/admin/quotas")
@RequiredArgsConstructor
public class AdminQuotaController {

    private final AdminQuotaService adminQuotaService;

    /**
     * 查询用户当前周期 quota 列表（分页 + 筛选）。
     *
     * @param page     页码，默认 1，最小 1
     * @param pageSize 每页条数，默认 20，范围 1~100
     * @param keyword  关键字（匹配 username / email）
     * @param role     角色过滤：USER / ADMIN
     * @param status   状态过滤：ALL / ACTIVE / DISABLED
     */
    @GetMapping
    public Result<AdminQuotaPageDto> list(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        requireAdmin(authUser);
        return Result.success(adminQuotaService.listQuotas(page, pageSize, keyword, role, status));
    }

    /**
     * 查询单个用户当前周期 quota。无 quota 行时返回虚拟默认视图，不创建行。
     */
    @GetMapping("/users/{userId}")
    public Result<AdminQuotaDto> getUserQuota(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long userId) {
        requireAdmin(authUser);
        return Result.success(adminQuotaService.getUserQuota(userId));
    }

    /**
     * 设置当前周期 creditsTotal。无 quota 行时创建当前周期 quota 行。
     */
    @PutMapping("/users/{userId}/total")
    public Result<AdminQuotaDto> setTotal(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long userId,
            @Valid @RequestBody AdminSetQuotaTotalRequest request) {
        requireAdmin(authUser);
        return Result.success(adminQuotaService.setTotal(userId, request.getCreditsTotal()));
    }

    /**
     * 给当前周期增加 creditsTotal。无 quota 行时创建（creditsTotal=30+credits）。
     */
    @PostMapping("/users/{userId}/grant")
    public Result<AdminQuotaDto> grantCredits(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long userId,
            @Valid @RequestBody AdminGrantCreditsRequest request) {
        requireAdmin(authUser);
        return Result.success(adminQuotaService.grantCredits(userId, request.getCredits()));
    }

    /**
     * 重置当前周期 creditsUsed=0。无 quota 行时创建默认 quota 行（30/0）。
     */
    @PostMapping("/users/{userId}/reset-used")
    public Result<AdminQuotaDto> resetUsed(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long userId) {
        requireAdmin(authUser);
        return Result.success(adminQuotaService.resetUsed(userId));
    }

    /**
     * 防御性 ADMIN 校验。
     *
     * <p>Spring Security 已在路由层做 {@code hasRole("ADMIN")} 拦截，
     * 此处做二次校验防止配置遗漏或内部直接调用绕过安全过滤。</p>
     */
    private void requireAdmin(AuthUser authUser) {
        if (authUser == null || !"ADMIN".equals(authUser.getRole())) {
            throw new AccessDeniedException("ADMIN required");
        }
    }
}
