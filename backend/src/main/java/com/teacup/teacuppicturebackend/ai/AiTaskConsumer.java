package com.teacup.teacuppicturebackend.ai;

import com.rabbitmq.client.Channel;
import com.teacup.teacuppicturebackend.config.AiRabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
public class AiTaskConsumer {
    private final AiTaskRunner runner;
    private final AiMessagePublisher publisher;

    public AiTaskConsumer(AiTaskRunner runner, AiMessagePublisher publisher) {
        this.runner = runner;
        this.publisher = publisher;
    }

    @RabbitListener(queues = AiRabbitConfig.TASK_QUEUE, containerFactory = "aiRabbitListenerContainerFactory")
    public void consume(AiTaskMessage payload, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        long taskId;
        try {
            taskId = Long.parseLong(payload.taskId());
        } catch (RuntimeException exception) {
            log.error("Rejected malformed AI task message, eventId={}", payload.eventId());
            channel.basicReject(deliveryTag, false);
            return;
        }

        try {
            AiTaskRunner.ExecutionResult result = runner.run(taskId);
            if (result.retry()) {
                try {
                    publisher.publishRetry(new AiTaskMessage(UUID.randomUUID().toString(), payload.taskId()),
                            result.delaySeconds());
                } catch (RuntimeException publishFailure) {
                    log.warn("Unable to publish AI task retry, taskId={}, reason={}", taskId, result.reason());
                    channel.basicNack(deliveryTag, false, true);
                    return;
                }
            }
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException exception) {
            log.error("Unexpected AI task consumer failure, taskId={}", taskId, exception);
            channel.basicReject(deliveryTag, false);
        }
    }
}
