package com.teacup.teacuppicturebackend.ai;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTaskConsumerTest {
    private final AiTaskRunner runner = mock(AiTaskRunner.class);
    private final AiMessagePublisher publisher = mock(AiMessagePublisher.class);
    private final AiTaskConsumer consumer = new AiTaskConsumer(runner, publisher);
    private final Channel channel = mock(Channel.class);

    @Test
    void acknowledgesCompletedTask() throws Exception {
        when(runner.run(31L)).thenReturn(AiTaskRunner.ExecutionResult.ack());

        consumer.consume(new AiTaskMessage("event-1", "31"), message(7L), channel);

        verify(channel).basicAck(7L, false);
        verify(publisher, never()).publishRetry(any(), any(Integer.class));
    }

    @Test
    void publishesConfirmedRetryBeforeAcknowledgingOriginalMessage() throws Exception {
        when(runner.run(31L)).thenReturn(AiTaskRunner.ExecutionResult.retry(30, "provider_rate_limited"));

        consumer.consume(new AiTaskMessage("event-1", "31"), message(8L), channel);

        verify(publisher).publishRetry(any(AiTaskMessage.class), eq(30));
        verify(channel).basicAck(8L, false);
    }

    @Test
    void requeuesOriginalWhenRetryPublishFails() throws Exception {
        when(runner.run(31L)).thenReturn(AiTaskRunner.ExecutionResult.retry(5, "limiter_unavailable"));
        org.mockito.Mockito.doThrow(new AiMessagePublisher.AiMessagePublishException("down"))
                .when(publisher).publishRetry(any(AiTaskMessage.class), eq(5));

        consumer.consume(new AiTaskMessage("event-1", "31"), message(9L), channel);

        verify(channel).basicNack(9L, false, true);
        verify(channel, never()).basicAck(9L, false);
    }

    @Test
    void rejectsMalformedTaskIdWithoutRequeue() throws Exception {
        consumer.consume(new AiTaskMessage("event-1", "not-an-id"), message(10L), channel);

        verify(channel).basicReject(10L, false);
        verify(runner, never()).run(any(Long.class));
    }

    private static Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message(new byte[0], properties);
    }
}
