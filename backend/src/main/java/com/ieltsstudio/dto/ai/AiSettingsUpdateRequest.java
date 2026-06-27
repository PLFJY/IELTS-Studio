package com.ieltsstudio.dto.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 更新当前用户 AI 设置的请求体。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>{@code keyMode}：{@code BUILTIN} / {@code USER}。</li>
 *   <li>{@code text} / {@code vision}：可为 {@code null}，为 null 时该类配置保持不变。</li>
 * </ul>
 *
 * <p>策略说明：本阶段<b>允许</b>在 USER 模式下保存不完整配置（例如先填 provider/baseUrl/model，
 * 暂不填 apiKey）；真正解析 USER credentials 时（{@code AiSettingsService.resolve}）才会校验
 * key 是否存在，缺失则抛清晰异常。这样前端可分步保存配置。</p>
 */
@Data
public class AiSettingsUpdateRequest {

    @NotBlank(message = "keyMode 不能为空")
    private String keyMode;

    @Valid
    private AiProviderConfigRequest text;

    @Valid
    private AiProviderConfigRequest vision;
}
