package com.teacup.teacuppicturebackend.ai;

import com.teacup.teacuppicturebackend.config.AiRabbitConfig;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AiMessagePublisher {
    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutSeconds;

    public AiMessagePublisher(RabbitTemplate rabbitTemplate,
                              @Value("${teacup.ai.worker.publish-confirm-timeout-seconds:5}")
                              long confirmTimeoutSeconds) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutSeconds = confirmTimeoutSeconds;
    }

    public void publishTask(AiTaskMessage message) {
        publish(AiRabbitConfig.TASK_ROUTING_KEY, message);
    }

    public void publishRetry(AiTaskMessage message, int delaySeconds) {
        String routingKey = delaySeconds <= 5 ? AiRabbitConfig.RETRY_5S_ROUTING_KEY
                : delaySeconds <= 30 ? AiRabbitConfig.RETRY_30S_ROUTING_KEY
                : AiRabbitConfig.RETRY_120S_ROUTING_KEY;
        publish(routingKey, message);
    }

    private void publish(String routingKey, AiTaskMessage message) {
        CorrelationData correlation = new CorrelationData(message.eventId());
        rabbitTemplate.convertAndSend(AiRabbitConfig.EXCHANGE, routingKey, message, outgoing -> {
            outgoing.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            outgoing.getMessageProperties().setHeader("x-ai-event-id", message.eventId());
            return outgoing;
        }, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeoutSeconds, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new AiMessagePublishException("RabbitMQ rejected AI task message: " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new AiMessagePublishException("RabbitMQ returned unroutable AI task message");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiMessagePublishException("Interrupted while waiting for RabbitMQ publisher confirm", exception);
        } catch (AiMessagePublishException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiMessagePublishException("RabbitMQ publisher confirm failed", exception);
        }
    }

    public static class AiMessagePublishException extends RuntimeException {
        public AiMessagePublishException(String message) {
            super(message);
        }

        public AiMessagePublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
