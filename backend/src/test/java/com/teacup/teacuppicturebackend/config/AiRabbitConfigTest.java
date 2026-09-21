package com.teacup.teacuppicturebackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.SynchronousQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AiRabbitConfigTest {
    private final AiRabbitConfig config = new AiRabbitConfig();

    @Test
    void taskQueueUsesQuorumAndAtLeastOnceDeadLettering() {
        Queue queue = config.aiTaskQueue();

        assertEquals("quorum", queue.getArguments().get("x-queue-type"));
        assertEquals("at-least-once", queue.getArguments().get("x-dead-letter-strategy"));
        assertEquals("reject-publish", queue.getArguments().get("x-overflow"));
        assertEquals(AiRabbitConfig.EXCHANGE, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(AiRabbitConfig.DEAD_LETTER_ROUTING_KEY,
                queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @Test
    void workerExecutorIsFixedSizeWithoutLocalBacklog() {
        ThreadPoolTaskExecutor executor = config.aiWorkerExecutor(4, 180);
        try {
            assertEquals(4, executor.getThreadPoolExecutor().getCorePoolSize());
            assertEquals(4, executor.getThreadPoolExecutor().getMaximumPoolSize());
            assertInstanceOf(SynchronousQueue.class, executor.getThreadPoolExecutor().getQueue());
        } finally {
            executor.shutdown();
        }
    }
}
