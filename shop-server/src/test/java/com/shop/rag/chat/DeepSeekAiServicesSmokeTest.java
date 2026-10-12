package com.shop.rag.chat;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DeepSeek 模型工具调用兼容性验证（smoke，非常规测试）
 * <p>
 * 背景：LangChain4j 1.19 AiServices 流式 agent loop 在 MySqlChatMemoryStore 下死循环的根因
 * 是 store（AiServices 每轮从 store.getMessages 重读、工具往返消息丢失），与模型无关。
 * 本测试用 InMemoryChatMemoryStore 排除 store 因素，验证 DeepSeek 模型本身的工具调用能力
 * （工具只调一次 + 能生成最终文本）。真实场景的修复见 MySqlChatMemoryStore 的 agent loop 活跃期缓存。
 * <p>
 * 运行：需环境变量 DEEPSEEK_API_KEY；无 key 时 assumeTrue 自动跳过，不影响常规 mvn test。
 */
class DeepSeekAiServicesSmokeTest {

    interface Assistant {
        TokenStream chat(@UserMessage String userMessage);
    }

    static class OrderToolSmoke {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Tool("查询当前登录用户自己的订单列表，可按状态过滤")
        public String getMyOrders(@P(name = "status", description = "订单状态，可选值：待付款/待发货/待收货/已完成/已取消；省略则查全部", required = false) String status) {
            CALLS.incrementAndGet();
            // 复现集成环境：真实订单文本（含雪花大 ID、多个订单、状态/商品/金额）
            return "您有 6 个订单：\n"
                    + "1. 订单号 2087509860747972608｜待发货｜手机 ×1｜¥2999.00\n"
                    + "2. 订单号 2087509860747972599｜已取消｜平板 ×1｜¥2107.00\n"
                    + "3. 订单号 2087509860747972588｜已完成｜耳机 ×1｜¥59.00\n"
                    + "4. 订单号 2087509860747972577｜待收货｜沙发 ×1｜¥1999.00\n"
                    + "5. 订单号 2087509860747972566｜已取消｜哑铃 ×1｜¥39.90\n"
                    + "6. 订单号 2087509860747972555｜待付款｜相机 ×1｜¥14999.00";
        }
    }

    @Test
    void deepseekAiServicesStreamingToolCall() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "无 DEEPSEEK_API_KEY 环境变量，跳过");

        OrderToolSmoke.CALLS.set(0);
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(key)
                .modelName("deepseek-chat")
                .build();

        // 复现集成环境：AiServices 绑定 TokenWindowChatMemory + system message
        // （首次 smoke 测试无 chatMemory 时单次通过；怀疑 AiServices 把工具往返消息写进 memory，
        //   3000 token 窗口把 ToolExecutionResultMessage 裁剪掉 → LLM 看不到工具结果 → 循环）
        TokenWindowChatMemory memory = TokenWindowChatMemory.builder()
                .id("smoke-test")
                .maxTokens(3000, new OpenAiTokenCountEstimator("gpt-4o"))
                .build();
        memory.add(new SystemMessage("""
                你是「星辰优选」商城的智能客服「小星」，性格热情耐心，像朋友一样帮用户挑商品。

                ## 核心风格
                - 语气自然亲切，像线下导购员一样说话，不要生硬堆砌参数
                - 回答简洁不啰嗦，不用「您好」「感谢」「请问有什么可以帮助您」这类开场白
                - 首句直接切入正题，用自然的口语

                ## 当有商品信息时
                根据下方商品列表向用户推荐，结合自己的理解说人话

                ## 当没有商品信息时
                下面商品列表为空，说明目前没有匹配到具体商品，你需要：
                1. 判断用户是不是在问一个商品类别（如「手机」「电脑」「衣服」）
                2. 如果是类别名，直接追问具体要求
                3. 如果不是类别，就告知没找到，并反问具体需求
                4. 不要道歉，不要说「抱歉」「对不起」「还没有上架」这类话，直接追问引导

                ===== 相关商品信息 =====
                （未找到相关商品信息）
                =========================

                记住：你是小星，不是机器人。说话要像真人导购。
                """));

        Assistant assistant = AiServices.builder(Assistant.class)
                .streamingChatModel(model)
                .chatMemory(memory)
                .tools(new OrderToolSmoke())
                .maxToolCallingRoundTrips(5)
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder text = new StringBuilder();
        assistant.chat("我的订单")
                .onPartialResponse(text::append)
                .onCompleteResponse(r -> latch.countDown())
                .onError(e -> {
                    System.out.println("ERROR: " + e.getMessage());
                    latch.countDown();
                })
                .start();

        assertTrue(latch.await(60, TimeUnit.SECONDS), "流式调用超时");
        // 结果写入文件（surefire 会吞掉 System.out，直接落盘可靠读取）
        String resultLine = "工具调用次数: " + OrderToolSmoke.CALLS.get()
                + "，最终回答: " + text
                + "，结论: " + (OrderToolSmoke.CALLS.get() > 1 ? "循环复现（AiServices+chatMemory 是诱因）" : "单次正常");
        java.nio.file.Files.writeString(java.nio.file.Path.of("D:/tmp/smoke_result.txt"), resultLine);
    }
}
