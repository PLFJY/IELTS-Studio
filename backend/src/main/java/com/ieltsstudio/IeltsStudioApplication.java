package com.ieltsstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * IELTS Studio 应用启动类
 *
 * <p>异步任务支持由 {@link com.ieltsstudio.config.AsyncConfig} 配置，
 * 用于试卷解析（{@link com.ieltsstudio.service.AsyncParseService}）
 * 和单词批量导入（{@link com.ieltsstudio.service.AsyncWordService}）的异步执行。
 */
@SpringBootApplication
public class IeltsStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(IeltsStudioApplication.class, args);
    }
}
