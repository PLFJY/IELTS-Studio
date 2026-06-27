package com.ieltsstudio.ai.service;

import com.ieltsstudio.ai.AiFeature;
import com.ieltsstudio.ai.AiKeyMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AiUsageGuard} 单元测试。
 *
 * <p>当前阶段为骨架 / no-op，仅验证基础参数校验存在，
 * 且正常参数下不抛异常。</p>
 */
class AiUsageGuardTest {

    private final AiUsageGuard guard = new AiUsageGuard();

    @Test
    void checkBeforeCallShouldThrowWhenUserIdNull() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.checkBeforeCall(null, AiFeature.AI_CHAT, AiKeyMode.BUILTIN));
    }

    @Test
    void checkBeforeCallShouldThrowWhenFeatureNull() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.checkBeforeCall(1L, null, AiKeyMode.BUILTIN));
    }

    @Test
    void checkBeforeCallShouldThrowWhenKeyModeNull() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.checkBeforeCall(1L, AiFeature.AI_CHAT, null));
    }

    @Test
    void checkBeforeCallShouldNotThrowWithValidParams() {
        assertDoesNotThrow(() -> guard.checkBeforeCall(1L, AiFeature.AI_CHAT, AiKeyMode.BUILTIN));
    }

    @Test
    void checkBeforeCallShouldNotThrowForUserKeyMode() {
        assertDoesNotThrow(() -> guard.checkBeforeCall(1L, AiFeature.EXAM_PRECISE_PARSE, AiKeyMode.USER));
    }

    @Test
    void markSuccessShouldThrowWhenUserIdNull() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.markSuccess(null, AiFeature.AI_CHAT, AiKeyMode.BUILTIN));
    }

    @Test
    void markSuccessShouldThrowWhenFeatureNull() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.markSuccess(1L, null, AiKeyMode.BUILTIN));
    }

    @Test
    void markSuccessShouldThrowWhenKeyModeNull() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.markSuccess(1L, AiFeature.AI_CHAT, null));
    }

    @Test
    void markSuccessShouldNotThrowWithValidParams() {
        assertDoesNotThrow(() -> guard.markSuccess(1L, AiFeature.AI_CHAT, AiKeyMode.BUILTIN));
    }

    @Test
    void markFailureShouldThrowWhenUserIdNull() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.markFailure(null, AiFeature.AI_CHAT, AiKeyMode.BUILTIN, new RuntimeException("x")));
    }

    @Test
    void markFailureShouldThrowWhenFeatureNull() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.markFailure(1L, null, AiKeyMode.BUILTIN, new RuntimeException("x")));
    }

    @Test
    void markFailureShouldThrowWhenKeyModeNull() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.markFailure(1L, AiFeature.AI_CHAT, null, new RuntimeException("x")));
    }

    @Test
    void markFailureShouldNotThrowWithValidParams() {
        assertDoesNotThrow(() ->
                guard.markFailure(1L, AiFeature.AI_CHAT, AiKeyMode.BUILTIN, new RuntimeException("test")));
    }

    @Test
    void markFailureShouldNotThrowWhenExceptionNull() {
        assertDoesNotThrow(() -> guard.markFailure(1L, AiFeature.AI_CHAT, AiKeyMode.BUILTIN, null));
    }
}
