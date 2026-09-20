package com.shop.rag;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 客服意图识别（规则优先 + LLM 兜底）
 * <p>
 * 规则层用强信号关键词做低延迟、确定性的意图判定（取消/查订单/闲聊）；
 * 规则不明确时才触发一次 LLM 分类兜底（命中订单域词，或「短句且无任何信号词」），
 * 避免每轮都引入额外 LLM 延迟；LLM 失败或无关消息默认 {@link Intent#PRODUCT}。
 * </p>
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatIntentResolver {

    @Qualifier("openAiStreamingChatModel")
    private final OpenAiStreamingChatModel streamingChatModel;

    private static final String CLASSIFY_SYSTEM = "你是电商客服的意图分类器，只输出一个英文单词，不要任何解释。";

    private static final String CLASSIFY_PROMPT_TEMPLATE = """
            把用户消息归类为以下四种意图之一，只输出对应英文单词（不要标点/解释）：
            - cancel：取消订单（写操作）——用户明确要退单/取消某笔订单
            - query：查询订单（只读）——用户问自己订单的状态/物流/买了什么
            - chitchat：闲聊——问候/道谢/告别/情绪倾诉/问你是谁/让你陪聊，且不涉及具体商品
            - product：商品咨询/导购——其余所有情况

            用户消息：%s

            意图：""";

    /** 闲聊强信号词（问候/致谢/告别/情绪/身份/陪聊）——命中且未命中商品信号词时判为闲聊 */
    private static final String[] CHITCHAT_WORDS = {
            "你好", "您好", "哈喽", "嗨", "在吗", "在么", "hi", "hello",
            "谢谢", "感谢", "多谢", "辛苦",
            "再见", "拜拜", "晚安", "早安",
            "聊天", "陪我聊", "陪我", "聊两句", "说说话", "讲个笑话", "讲个故事", "讲故事", "唱首歌",
            "无聊", "心情", "难过", "郁闷", "孤单", "寂寞", "安慰",
            "你是谁", "你叫什么", "你会什么", "你能干什么", "你能做什么", "你多大", "你是机器人",
            "介绍下你", "介绍一下你"
    };

    /**
     * 商品信号词（闲聊守门用）：命中则一律按商品意图走，避免「陪我看看手机」这类
     * 「闲聊词 + 商品词」混合句被误判成闲聊、白跑一趟不推商品。
     * 只列高频词不求穷举——漏网的长尾句仍按原有 PRODUCT 链路处理，行为不劣于改造前。
     */
    private static final String[] PRODUCT_SIGNAL_WORDS = {
            "买", "购", "价", "钱", "便宜", "贵", "推荐", "哪款", "哪个", "怎么样", "值得", "对比",
            "参数", "配置", "型号", "尺寸", "颜色", "尺码", "品牌", "优惠", "活动", "券", "礼物",
            "手机", "电脑", "相机", "耳机", "音响", "冰箱", "空调", "洗衣机", "电视", "鞋", "衣服", "包",
            "面膜", "护肤", "化妆品", "零食", "玩具", "床品", "被子", "家具"
    };

    /** 「短句且无任何信号词」的长度上限：这类消息交给 LLM 分类兜底，避免漏判词表没覆盖的闲聊 */
    private static final int AMBIGUOUS_MAX_LEN = 12;

    /**
     * 识别用户消息意图
     *
     * @param message 用户消息
     * @return 意图（永不返回 null，LLM 兜底失败时默认 PRODUCT）
     */
    public Intent resolve(String message) {
        if (message == null || message.isBlank()) {
            return Intent.PRODUCT;
        }

        // ① 规则优先：取消订单（写操作）强信号
        if (hasAny(message, "取消", "退单", "退掉", "退了", "不想要了", "拒收", "别发货", "别发")) {
            return Intent.ORDER_CANCEL;
        }

        // ② 规则优先：查订单（只读）强信号
        if (hasAny(message, "我的订单", "订单列表", "有哪些订单", "什么订单", "有什么订单", "都买了什么",
                "待收货", "待发货", "待付款", "快递", "物流")) {
            return Intent.ORDER_QUERY;
        }
        if (message.contains("订单") && hasAny(message, "到哪", "发货", "状态", "进度", "查", "看一下", "催",
                "哪些", "列表", "有啥", "几个")) {
            return Intent.ORDER_QUERY;
        }

        // ③ 规则不明确 → LLM 兜底一次（cancel/query/chitchat/product）：
        //    条件一：命中订单域词（模糊指代的订单问题）；条件二：短句且无任何信号词
        //    （如「在干嘛」「你是deepseek吧」这类词表没覆盖的闲聊）
        boolean orderDomainHit = message.contains("订单") || message.contains("单") || message.contains("快递")
                || message.contains("退") || message.contains("取消");
        boolean ambiguousShort = message.length() <= AMBIGUOUS_MAX_LEN
                && !hasAny(message, PRODUCT_SIGNAL_WORDS) && !hasAny(message, CHITCHAT_WORDS);
        if (orderDomainHit || ambiguousShort) {
            Intent llmIntent = classifyByLlm(message);
            if (llmIntent != null) {
                return llmIntent;
            }
        }

        // ④ 规则优先：闲聊强信号且无明显商品意图 → CHITCHAT
        //    闲聊不检索商品、不推商品卡片：否则「能不能陪我聊天」会被拿去搜货架，
        //    把毫不相关的商品卡片硬塞进闲聊回答（实测：闲聊被搜出蓝牙耳机/跳绳/扫地机器人并推送卡片）
        if (hasAny(message, CHITCHAT_WORDS) && !hasAny(message, PRODUCT_SIGNAL_WORDS)) {
            return Intent.CHITCHAT;
        }

        return Intent.PRODUCT;
    }

    /** 关键词命中判定：任一关键词出现即命中（订单域与闲聊规则共用） */
    private boolean hasAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * LLM 兜底分类：一次非流式调用，输出 cancel/query/product 之一；
     * 失败或输出无法识别返回 null（调用方回默认 PRODUCT）
     */
    private Intent classifyByLlm(String message) {
        String prompt = String.format(CLASSIFY_PROMPT_TEMPLATE, message);
        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            List<ChatMessage> messages = List.of(
                    new SystemMessage(CLASSIFY_SYSTEM),
                    new UserMessage(prompt));

            streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
                private final StringBuilder sb = new StringBuilder();

                @Override
                public void onPartialResponse(String token) {
                    sb.append(token);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    future.complete(sb.toString().trim());
                }

                @Override
                public void onError(Throwable error) {
                    future.completeExceptionally(error);
                }
            });

            String raw = future.get(8, TimeUnit.SECONDS).toLowerCase(Locale.ROOT);
            if (raw.contains("cancel")) {
                return Intent.ORDER_CANCEL;
            }
            if (raw.contains("query")) {
                return Intent.ORDER_QUERY;
            }
            if (raw.contains("chitchat")) {
                return Intent.CHITCHAT;
            }
            if (raw.contains("product")) {
                return Intent.PRODUCT;
            }
            return null;
        } catch (Exception e) {
            log.debug("LLM 意图分类失败，回默认意图: {}", e.getMessage());
            return null;
        }
    }
}
