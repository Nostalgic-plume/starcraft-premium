package com.shop.rag;

import com.shop.mapper.ChatFeedbackMapper;
import com.shop.model.entity.ChatFeedbackDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对话反馈服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFeedbackService {

    private final ChatFeedbackMapper feedbackMapper;

    /**
     * 提交反馈
     *
     * @param messageId    消息ID
     * @param userId       用户ID
     * @param rating       评分：1=有用 0=无用 -1=错误
     * @param feedbackText 反馈文字（可选）
     * @return 反馈记录ID
     */
    public Long submitFeedback(Long messageId, Long userId, Integer rating, String feedbackText) {
        ChatFeedbackDO feedback = new ChatFeedbackDO();
        feedback.setMessageId(messageId);
        feedback.setUserId(userId);
        feedback.setRating(rating);
        feedback.setFeedbackText(feedbackText);
        feedbackMapper.insert(feedback);
        log.info("用户反馈已记录: messageId={}, userId={}, rating={}", messageId, userId, rating);
        return feedback.getId();
    }
}
