package com.ieltsstudio.service;

import com.ieltsstudio.dto.admin.AdminOperationLogDto;
import com.ieltsstudio.dto.admin.AdminOperationLogPageDto;
import com.ieltsstudio.entity.AdminOperationAction;
import com.ieltsstudio.entity.AdminOperationLog;
import com.ieltsstudio.entity.AdminOperationStatus;
import com.ieltsstudio.mapper.AdminOperationLogMapper;
import com.ieltsstudio.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminAuditLogService} 单元测试（Phase 8D）。
 *
 * <p>不连接真实数据库：{@link AdminOperationLogMapper} 用 Mockito mock。
 * 覆盖 summary 脱敏、FAILED 状态写入、summary 截断、分页 clamp、DTO 不暴露敏感字段。</p>
 */
class AdminAuditLogServiceTest {

    private AdminOperationLogMapper auditLogMapper;
    private AdminAuditLogService service;

    @BeforeEach
    void setUp() {
        auditLogMapper = mock(AdminOperationLogMapper.class);
        service = new AdminAuditLogService(auditLogMapper);
    }

    // ─── 1. recordSuccessShouldInsertSanitizedLog ──────────────────────────────

    @Test
    void recordSuccessShouldInsertSanitizedLog() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10, 10.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0 (Test)");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        // summary 包含 Bearer / sk- / password 三种敏感关键字（按规格只脱敏关键字本身，
        // 不脱敏 = 后的值；调用方契约：重置密码场景只记 "reset password for userId=xxx"，不传密码值）
        String dirty = "Bearer eyJabc123, sk-deepseek-ABC123, password reset note, normal text";
        service.recordSuccess(
                new AuthUser(1L, "admin", "ADMIN"),
                AdminOperationAction.USER_RESET_PASSWORD,
                "USER",
                42L,
                42L,
                dirty,
                request);

        // 应调用 insert 一次
        verify(auditLogMapper, times(1)).insert(any(AdminOperationLog.class));

        // 捕获传入的 entity，校验 status / summary 脱敏 / IP / UA
        org.mockito.ArgumentCaptor<AdminOperationLog> captor =
                org.mockito.ArgumentCaptor.forClass(AdminOperationLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AdminOperationLog saved = captor.getValue();

        assertEquals(AdminOperationStatus.SUCCESS.name(), saved.getStatus(),
                "recordSuccess 应写入 SUCCESS 状态");
        assertEquals(AdminOperationAction.USER_RESET_PASSWORD.name(), saved.getAction());
        assertEquals("203.0.113.10", saved.getIpAddress(),
                "应取 X-Forwarded-For 第一个 IP");
        assertEquals("Mozilla/5.0 (Test)", saved.getUserAgent());
        // 三个敏感关键字都应被替换为 ***
        assertFalse(saved.getSummary().contains("Bearer"), "summary 不应含 Bearer");
        assertFalse(saved.getSummary().contains("sk-deepseek-ABC123"), "summary 不应含 sk- key");
        assertFalse(saved.getSummary().toLowerCase().contains("password"),
                "summary 不应含 password 关键字（不区分大小写）");
        assertTrue(saved.getSummary().contains("***"), "summary 应含脱敏占位符 ***");
        assertTrue(saved.getSummary().contains("normal text"), "非敏感内容应保留");
    }

    // ─── 2. recordFailureShouldInsertFailedStatus ──────────────────────────────

    @Test
    void recordFailureShouldInsertFailedStatus() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(eq("X-Forwarded-For"))).thenReturn(null);
        when(request.getHeader(eq("X-Real-IP"))).thenReturn("198.51.100.7");
        when(request.getHeader(eq("User-Agent"))).thenReturn("curl/8.0");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        service.recordFailure(
                new AuthUser(2L, "admin2", "ADMIN"),
                AdminOperationAction.QUOTA_SET_TOTAL,
                "QUOTA",
                null,
                99L,
                "set creditsTotal=100 failed: RuntimeException",
                request);

        org.mockito.ArgumentCaptor<AdminOperationLog> captor =
                org.mockito.ArgumentCaptor.forClass(AdminOperationLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AdminOperationLog saved = captor.getValue();

        assertEquals(AdminOperationStatus.FAILED.name(), saved.getStatus(),
                "recordFailure 应写入 FAILED 状态");
        assertEquals("198.51.100.7", saved.getIpAddress(),
                "X-Forwarded-For 缺失时应回退到 X-Real-IP");
        assertEquals("curl/8.0", saved.getUserAgent());
    }

    // ─── 3. summaryShouldBeTruncatedTo1000Chars ────────────────────────────────

    @Test
    void summaryShouldBeTruncatedTo1000Chars() {
        // 构造 2000 字符的纯文本 summary（无敏感关键字）
        StringBuilder sb = new StringBuilder(2000);
        for (int i = 0; i < 2000; i++) {
            sb.append('x');
        }
        String longSummary = sb.toString();

        // 直接调 sanitizeSummary 验证截断
        String sanitized = service.sanitizeSummary(longSummary);
        assertNotNull(sanitized);
        assertEquals(1000, sanitized.length(), "summary 应被截断到 1000 字符");

        // 通过 recordSuccess 验证入库前也会截断
        service.recordSuccess(
                new AuthUser(1L, "admin", "ADMIN"),
                AdminOperationAction.USER_DISABLE,
                "USER",
                1L,
                1L,
                longSummary,
                null);

        org.mockito.ArgumentCaptor<AdminOperationLog> captor =
                org.mockito.ArgumentCaptor.forClass(AdminOperationLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertEquals(1000, captor.getValue().getSummary().length(),
                "入库的 summary 应被截断到 1000 字符");
    }

    // ─── 4. listLogsShouldClampPageAndPageSize ─────────────────────────────────

    @Test
    void listLogsShouldClampPageAndPageSize() {
        when(auditLogMapper.countAdminOperationLogs(
                anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(150L);
        when(auditLogMapper.selectAdminOperationLogs(
                anyInt(), anyInt(), anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        // page=0（应 clamp 到 1）+ pageSize=500（应 clamp 到 100）
        AdminOperationLogPageDto dto = service.listLogs(
                0, 500, 1L, 2L, "USER_CREATE", "USER", "SUCCESS", null, null);

        assertEquals(1L, dto.getPage(), "page<1 应 clamp 到 1");
        assertEquals(100L, dto.getPageSize(), "pageSize>100 应 clamp 到 100");
        assertEquals(150L, dto.getTotal());
        assertEquals(2L, dto.getPages(), "150 条 / 100 每页 = 2 页");

        // 验证 mapper 被调用时 offset/pageSize 已是 clamp 后的值
        verify(auditLogMapper).selectAdminOperationLogs(
                eq(0), eq(100), eq(1L), eq(2L), eq("USER_CREATE"),
                eq("USER"), eq("SUCCESS"), isNull(), isNull());
    }

    // ─── 5. dtoShouldNotExposeSensitiveFields ──────────────────────────────────

    @Test
    void dtoShouldNotExposeSensitiveFields() {
        // AdminOperationLogDto 不应含 password / apiKey / token / encrypted / masked 等敏感字段
        assertFalse(hasField(AdminOperationLogDto.class, "password"),
                "AdminOperationLogDto 不应含 password");
        assertFalse(hasField(AdminOperationLogDto.class, "newPassword"),
                "AdminOperationLogDto 不应含 newPassword");
        assertFalse(hasField(AdminOperationLogDto.class, "apiKey"),
                "AdminOperationLogDto 不应含 apiKey");
        assertFalse(hasField(AdminOperationLogDto.class, "api_key"),
                "AdminOperationLogDto 不应含 api_key");
        assertFalse(hasField(AdminOperationLogDto.class, "token"),
                "AdminOperationLogDto 不应含 token");
        assertFalse(hasField(AdminOperationLogDto.class, "encryptedKey"),
                "AdminOperationLogDto 不应含 encryptedKey");
        assertFalse(hasField(AdminOperationLogDto.class, "maskedKey"),
                "AdminOperationLogDto 不应含 maskedKey");
        assertFalse(hasField(AdminOperationLogDto.class, "authorization"),
                "AdminOperationLogDto 不应含 authorization");
    }

    // ─── helper ─────────────────────────────────────────────────────────────────

    private boolean hasField(Class<?> clazz, String fieldName) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getName().equalsIgnoreCase(fieldName)) {
                return true;
            }
        }
        return false;
    }
}
