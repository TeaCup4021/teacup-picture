package com.teacup.teacuppicturebackend.ai;

import com.rabbitmq.client.Channel;
import com.teacup.teacuppicturebackend.config.AiRabbitConfig;
import com.teacup.teacuppicturebackend.mapper.AiTaskMapper;
import com.teacup.teacuppicturebackend.model.entity.AiTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 死信队列消费者。
 * 死信队列此前只有声明没有消费者，进入死信的任务会静默悬挂在 queued，配额预占永久泄漏。
 * 本组件让这些任务有明确归宿：终态化并归还预占，且不重新投递（避免死循环）。
 */
@Slf4j
@Component
public class AiTaskDlqService {
    private final AiTaskMapper taskMapper;
    private final AiTaskService taskService;

    public AiTaskDlqService(AiTaskMapper taskMapper, AiTaskService taskService) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
    }

    @RabbitListener(queues = AiRabbitConfig.DEAD_LETTER_QUEUE, containerFactory = "aiDlqListenerContainerFactory")
    public void onDeadLetter(AiTaskMessage payload, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        if (payload == null || payload.taskId() == null) {
            log.error("Rejected malformed AI DLQ message");
            channel.basicReject(deliveryTag, false);
            return;
        }
        long taskId;
        try {
            taskId = Long.parseLong(payload.taskId());
        } catch (RuntimeException exception) {
            log.error("Rejected malformed AI DLQ task id, eventId={}", payload.eventId());
            channel.basicReject(deliveryTag, false);
            return;
        }
        try {
            AiTask task = taskMapper.selectById(taskId);
            if (task == null || taskService.isTerminal(task.getStatus())) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            log.error("AI task routed to DLQ, taskId={}, status={}, attempts={}",
                    taskId, task.getStatus(), task.getAttemptCount());
            taskService.exhaust(taskId, "dead_letter", "AI 任务被路由到死信队列，已回收");
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException exception) {
            log.error("Failed to handle AI DLQ message, taskId={}", taskId, exception);
            channel.basicReject(deliveryTag, false);
        }
    }
}
