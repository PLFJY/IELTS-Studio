package com.ieltsstudio.dto.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DTO {@code toString()} 安全性测试。
 *
 * <p>目标：确认 {@link AiProviderConfigRequest} 与 {@link AiSettingsUpdateRequest}
 * 的 {@code toString()} 不会输出明文 {@code apiKey}，避免 key 被打印到日志或异常栈。</p>
 *
 * <p>测试字符串：{@code sk-test-secret-123456}（非真实 Key）。</p>
 */
class AiDtoToStringTest {

    private static final String SECRET_KEY = "sk-test-secret-123456";

    @Test
    void aiProviderConfigRequestToStringShouldNotLeakApiKey() {
        AiProviderConfigRequest req = new AiProviderConfigRequest();
        req.setProvider("DEEPSEEK");
        req.setBaseUrl("https://api.deepseek.com");
        req.setModel("deepseek-chat");
        req.setApiKey(SECRET_KEY);
        req.setClearApiKey(false);

        String s = req.toString();
        assertNotNull(s);
        // toString 必须包含其他字段（证明 toString 真的生成了内容）
        assertTrue(s.contains("DEEPSEEK"), "toString 应当包含 provider 字段");
        assertTrue(s.contains("deepseek-chat"), "toString 应当包含 model 字段");
        // 关键断言：不包含明文 apiKey
        assertFalse(s.contains(SECRET_KEY), "toString 不能泄露明文 apiKey");
        assertFalse(s.contains("sk-test-secret"), "toString 不能包含 apiKey 任何片段");
        assertFalse(s.contains("123456"), "toString 不能包含 apiKey 尾部片段");
    }

    @Test
    void aiSettingsUpdateRequestToStringShouldNotLeakNestedApiKey() {
        AiProviderConfigRequest text = new AiProviderConfigRequest();
        text.setProvider("DEEPSEEK");
        text.setBaseUrl("https://api.deepseek.com");
        text.setModel("deepseek-chat");
        text.setApiKey(SECRET_KEY);
        text.setClearApiKey(false);

        AiProviderConfigRequest vision = new AiProviderConfigRequest();
        vision.setProvider("QWEN");
        vision.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        vision.setModel("qwen3.6-plus");
        vision.setApiKey(SECRET_KEY);
        vision.setClearApiKey(false);

        AiSettingsUpdateRequest req = new AiSettingsUpdateRequest();
        req.setKeyMode("USER");
        req.setText(text);
        req.setVision(vision);

        String s = req.toString();
        assertNotNull(s);
        // 外层 toString 应当包含 keyMode 与嵌套对象信息（证明 toString 真的递归生成了内容）
        assertTrue(s.contains("USER"), "toString 应当包含 keyMode");
        assertTrue(s.contains("DEEPSEEK"), "toString 应当包含 text.provider");
        assertTrue(s.contains("QWEN"), "toString 应当包含 vision.provider");
        // 关键断言：嵌套 text / vision 的明文 apiKey 不会被间接输出
        assertFalse(s.contains(SECRET_KEY), "toString 不能泄露嵌套 text/vision 的明文 apiKey");
        assertFalse(s.contains("sk-test-secret"), "toString 不能包含 apiKey 任何片段");
        assertFalse(s.contains("123456"), "toString 不能包含 apiKey 尾部片段");
    }
}
