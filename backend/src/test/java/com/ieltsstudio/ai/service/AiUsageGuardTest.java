package com.ieltsstudio.ai.service;

import com.ieltsstudio.ai.AiFeature;
import com.ieltsstudio.ai.AiKeyMode;
import com.ieltsstudio.entity.AiUsageQuota;
import com.ieltsstudio.entity.AiUsageRecord;
import com.ieltsstudio.mapper.AiUsageQuotaMapper;
import com.ieltsstudio.mapper.AiUsageRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiUsageGuard} 单元测试（Phase 6A）。
 *
 * <p>不连接真实数据库：{@link AiUsageQuotaMapper} / {@link AiUsageRecordMapper} 用 Mockito mock。
 * 验证 BUILTIN 预扣+回滚、USER 内存限流、流水写入与 errorMessage 脱敏。</p>
 */
class AiUsageGuardTest {

    private static final Long USER_ID = 1L;

    private AiUsageQuotaMapper quotaMapper;
    private AiUsageRecordMapper recordMapper;
    private AiUsageGuard guard;

    @BeforeEach
    void setUp() {
        quotaMapper = mock(AiUsageQuotaMapper.class);
        recordMapper = mock(AiUsageRecordMapper.class);
        guard = new AiUsageGuard(quotaMapper, recordMapper);
    }

    // ─── 1. BUILTIN：当前周期无 quota 时创建并预扣 ─────────────────────────────

    @Test
    void builtinShouldCreateQuotaAndReserveCredits() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        when(quotaMapper.insert(any(AiUsageQuota.class))).thenReturn(1);
        when(quotaMapper.update(any(), any())).thenReturn(1); // 预扣成功

        guard.checkBeforeCall(USER_ID, AiFeature.WRITING_GRADE, AiKeyMode.BUILTIN);

        // 创建 quota：creditsTotal=30, creditsUsed=0
        ArgumentCaptor<AiUsageQuota> quotaCaptor = ArgumentCaptor.forClass(AiUsageQuota.class);
        verify(quotaMapper).insert(quotaCaptor.capture());
        AiUsageQuota created = quotaCaptor.getValue();
        assertEquals(30, created.getCreditsTotal());
        assertEquals(0, created.getCreditsUsed());

        // 原子预扣执行
        verify(quotaMapper).update(any(), any());

        // 未写 REJECTED 流水
        verify(recordMapper, never()).insert(any(AiUsageRecord.class));
    }

    // ─── 2. BUILTIN：额度不足拒绝 ──────────────────────────────────────────────

    @Test
    void builtinShouldRejectWhenCreditsInsufficient() {
        AiUsageQuota quota = new AiUsageQuota();
        quota.setId(100L);
        quota.setUserId(USER_ID);
        quota.setCreditsTotal(30);
        quota.setCreditsUsed(30); // 已用完
        when(quotaMapper.selectOne(any())).thenReturn(quota);
        when(quotaMapper.update(any(), any())).thenReturn(0); // 预扣失败

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.checkBeforeCall(USER_ID, AiFeature.WRITING_GRADE, AiKeyMode.BUILTIN));
        assertTrue(ex.getMessage().contains("额度已用完"));

        // 未创建新 quota
        verify(quotaMapper, never()).insert(any(AiUsageQuota.class));
        // 写 REJECTED 流水
        ArgumentCaptor<AiUsageRecord> recCaptor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(recordMapper).insert(recCaptor.capture());
        AiUsageRecord rec = recCaptor.getValue();
        assertEquals("REJECTED", rec.getStatus());
        assertEquals(0, rec.getCost());
        assertEquals("BUILTIN", rec.getKeyMode());
        assertEquals("WRITING_GRADE", rec.getFeature());
        assertEquals("INSUFFICIENT_CREDITS", rec.getErrorMessage());
    }

    // ─── 3. BUILTIN markSuccess：写 SUCCESS 流水，cost = feature cost ──────────

    @Test
    void builtinMarkSuccessShouldWriteSuccessRecord() {
        guard.markSuccess(USER_ID, AiFeature.WRITING_GRADE, AiKeyMode.BUILTIN);

        // 已预扣，此处不再扣费
        verify(quotaMapper, never()).update(any(), any());
        verify(quotaMapper, never()).insert(any(AiUsageQuota.class));

        ArgumentCaptor<AiUsageRecord> recCaptor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(recordMapper).insert(recCaptor.capture());
        AiUsageRecord rec = recCaptor.getValue();
        assertEquals("SUCCESS", rec.getStatus());
        assertEquals(AiFeature.WRITING_GRADE.getBuiltinCost(), rec.getCost());
        assertEquals("WRITING_GRADE", rec.getFeature());
        assertEquals("TEXT", rec.getTaskType());
        assertEquals("BUILTIN", rec.getKeyMode());
        assertNull(rec.getErrorMessage());
    }

    // ─── 4. BUILTIN markFailure：回滚 credits + 写 FAILED 流水 ──────────────────

    @Test
    void builtinMarkFailureShouldRefundAndWriteFailedRecord() {
        Exception ex = new RuntimeException("connection timeout");

        guard.markFailure(USER_ID, AiFeature.EXAM_PRECISE_PARSE, AiKeyMode.BUILTIN, ex);

        // 执行了 quota 回滚
        verify(quotaMapper).update(any(), any());

        ArgumentCaptor<AiUsageRecord> recCaptor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(recordMapper).insert(recCaptor.capture());
        AiUsageRecord rec = recCaptor.getValue();
        assertEquals("FAILED", rec.getStatus());
        assertEquals(0, rec.getCost()); // 失败不消耗 credits
        assertEquals("EXAM_PRECISE_PARSE", rec.getFeature());
        assertEquals("VISION", rec.getTaskType());
        assertEquals("BUILTIN", rec.getKeyMode());
        assertNotNull(rec.getErrorMessage());
        assertFalse(rec.getErrorMessage().contains("sk-"));
    }

    // ─── 5. USER 模式：不触碰 quota，仅写 SUCCESS 流水（cost=0） ──────────────

    @Test
    void userModeShouldNotTouchQuotaButWriteSuccessRecord() {
        guard.checkBeforeCall(USER_ID, AiFeature.TRANSLATE, AiKeyMode.USER);
        guard.markSuccess(USER_ID, AiFeature.TRANSLATE, AiKeyMode.USER);

        // 完全不触碰 quota
        verify(quotaMapper, never()).selectOne(any());
        verify(quotaMapper, never()).insert(any(AiUsageQuota.class));
        verify(quotaMapper, never()).update(any(), any());

        ArgumentCaptor<AiUsageRecord> recCaptor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(recordMapper).insert(recCaptor.capture());
        AiUsageRecord rec = recCaptor.getValue();
        assertEquals("SUCCESS", rec.getStatus());
        assertEquals(0, rec.getCost());
        assertEquals("USER", rec.getKeyMode());
        assertEquals("TRANSLATE", rec.getFeature());
    }

    // ─── 6. USER 限流：超过 20 次/分钟后拒绝 ──────────────────────────────────

    @Test
    void userRateLimitShouldRejectAfterLimit() {
        // 前 20 次通过
        for (int i = 0; i < 20; i++) {
            guard.checkBeforeCall(USER_ID, AiFeature.AI_CHAT, AiKeyMode.USER);
        }
        // 第 21 次被限流
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.checkBeforeCall(USER_ID, AiFeature.AI_CHAT, AiKeyMode.USER));
        assertTrue(ex.getMessage().contains("请求过于频繁"));

        // 仅第 21 次写了一条 REJECTED 流水
        ArgumentCaptor<AiUsageRecord> recCaptor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(recordMapper).insert(recCaptor.capture());
        AiUsageRecord rec = recCaptor.getValue();
        assertEquals("REJECTED", rec.getStatus());
        assertEquals(0, rec.getCost());
        assertEquals("USER", rec.getKeyMode());
        assertEquals("RATE_LIMITED", rec.getErrorMessage());
    }

    // ─── 7. markFailure：errorMessage 脱敏 API Key / Authorization ─────────────

    @Test
    void failureRecordShouldSanitizeApiKeyAndAuthorization() {
        Exception ex = new RuntimeException(
                "Authorization: Bearer sk-test-secret-123 invalid key sk-test-secret-123");

        guard.markFailure(USER_ID, AiFeature.AI_CHAT, AiKeyMode.BUILTIN, ex);

        ArgumentCaptor<AiUsageRecord> recCaptor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(recordMapper).insert(recCaptor.capture());
        AiUsageRecord rec = recCaptor.getValue();
        String msg = rec.getErrorMessage();
        assertNotNull(msg);
        assertFalse(msg.contains("sk-test-secret"), "errorMessage 不得包含原始 key");
        assertFalse(msg.contains("Authorization: Bearer sk-test-secret"),
                "errorMessage 不得包含原始 Authorization 头");
        assertTrue(msg.contains("[REDACTED]"), "脱敏后应包含 [REDACTED]");
    }

    // ─── 8. 参数校验：null userId / feature / keyMode 一律拒绝 ─────────────────

    @Test
    void validateShouldRejectNullUserIdFeatureOrKeyMode() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.checkBeforeCall(null, AiFeature.WRITING_GRADE, AiKeyMode.BUILTIN));
        assertThrows(IllegalArgumentException.class,
                () -> guard.checkBeforeCall(USER_ID, null, AiKeyMode.BUILTIN));
        assertThrows(IllegalArgumentException.class,
                () -> guard.checkBeforeCall(USER_ID, AiFeature.WRITING_GRADE, null));
    }
}
