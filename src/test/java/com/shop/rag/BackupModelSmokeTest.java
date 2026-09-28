package com.shop.rag;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备用对话模型装配与可用性验证（smoke，非常规测试）
 * <p>
 * 背景：DashScope 停用后备用模型由 qwen-plus 换为智谱 glm-4-flash（主备异构容灾语义不变）。
 * 本测试走 **Spring 上下文**注入真实 Bean，验证「配置绑定 → 端点/Key/模型名 → 真实调用」整条链可用，
 * 即降级链的最后一环（DeepSeek 熔断 → 备用模型 → 模板兜底）具备实际生成能力，而不只是配置存在。
 * <p>
 * ⚠️ 与 {@code DeepSeekAiServicesSmokeTest} 的区别：后者自建模型实例、不加载上下文，
 * 测的是模型本身；本测试必须用 {@code @SpringBootTest} —— 否则测不出配置绑定是否正确。
 * <p>
 * 运行：需项目根 {@code .env} 提供 {@code BACKUP_LLM_API_KEY}（spring-dotenv 注入）；
 * 未配置时备用 Bean 的 Key 为空，调用必然失败，故此处直接断言而非 assumeTrue —— 静默跳过会让
 * 「降级链断了」这一事实被 CI 掩盖。离线环境请显式排除本测试。
 */
@SpringBootTest
class BackupModelSmokeTest {

    @Autowired
    @Qualifier("openAiStreamingChatModelBackup")
    private OpenAiStreamingChatModel backupModel;

    @Test
    void backupModelResponds() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder text = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<String> usage = new AtomicReference<>("n/a");

        backupModel.chat(
                List.of(new UserMessage("请只回复两个字：正常")),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        if (token != null) {
                            text.append(token);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        if (response != null && response.tokenUsage() != null) {
                            usage.set(response.tokenUsage().inputTokenCount()
                                    + "/" + response.tokenUsage().outputTokenCount());
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable e) {
                        error.set(e);
                        latch.countDown();
                    }
                });

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        String result = "备用模型调用：完成=" + completed
                + "，回答=" + text
                + "，token(输入/输出)=" + usage.get()
                + "，错误=" + (error.get() == null ? "无" : error.get().getMessage());
        // surefire 会吞掉 System.out，落盘保证结果可读
        Files.writeString(Path.of("D:/tmp/backup_model_result.txt"), result);

        assertTrue(completed, "备用模型调用超时未返回");
        assertTrue(error.get() == null, "备用模型调用报错：" + error.get());
        assertTrue(!text.isEmpty(), "备用模型未返回任何内容");
    }
}
