package com.teacup.teacuppicturebackend.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableRabbit
public class AiRabbitConfig {
    public static final String EXCHANGE = "teacup.ai.v1";
    public static final String TASK_QUEUE = "teacup.ai.task.v1";
    public static final String TASK_ROUTING_KEY = "ai.task";
    public static final String RETRY_5S_QUEUE = "teacup.ai.task.retry.5s.v1";
    public static final String RETRY_5S_ROUTING_KEY = "ai.task.retry.5s";
    public static final String RETRY_30S_QUEUE = "teacup.ai.task.retry.30s.v1";
    public static final String RETRY_30S_ROUTING_KEY = "ai.task.retry.30s";
    public static final String RETRY_120S_QUEUE = "teacup.ai.task.retry.120s.v1";
    public static final String RETRY_120S_ROUTING_KEY = "ai.task.retry.120s";
    public static final String DEAD_LETTER_QUEUE = "teacup.ai.task.dlq.v1";
    public static final String DEAD_LETTER_ROUTING_KEY = "ai.task.dead";

    @Bean
    public DirectExchange aiExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue aiTaskQueue() {
        return QueueBuilder.durable(TASK_QUEUE)
                .quorum()
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue aiRetry5sQueue() {
        return retryQueue(RETRY_5S_QUEUE, 5_000);
    }

    @Bean
    public Queue aiRetry30sQueue() {
        return retryQueue(RETRY_30S_QUEUE, 30_000);
    }

    @Bean
    public Queue aiRetry120sQueue() {
        return retryQueue(RETRY_120S_QUEUE, 120_000);
    }

    @Bean
    public Queue aiDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build();
    }

    @Bean
    public Binding aiTaskBinding(DirectExchange aiExchange, Queue aiTaskQueue) {
        return BindingBuilder.bind(aiTaskQueue).to(aiExchange).with(TASK_ROUTING_KEY);
    }

    @Bean
    public Binding aiRetry5sBinding(DirectExchange aiExchange, Queue aiRetry5sQueue) {
        return BindingBuilder.bind(aiRetry5sQueue).to(aiExchange).with(RETRY_5S_ROUTING_KEY);
    }

    @Bean
    public Binding aiRetry30sBinding(DirectExchange aiExchange, Queue aiRetry30sQueue) {
        return BindingBuilder.bind(aiRetry30sQueue).to(aiExchange).with(RETRY_30S_ROUTING_KEY);
    }

    @Bean
    public Binding aiRetry120sBinding(DirectExchange aiExchange, Queue aiRetry120sQueue) {
        return BindingBuilder.bind(aiRetry120sQueue).to(aiExchange).with(RETRY_120S_ROUTING_KEY);
    }

    @Bean
    public Binding aiDeadLetterBinding(DirectExchange aiExchange, Queue aiDeadLetterQueue) {
        return BindingBuilder.bind(aiDeadLetterQueue).to(aiExchange).with(DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter aiMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean(name = "aiWorkerExecutor")
    public ThreadPoolTaskExecutor aiWorkerExecutor(
            @Value("${teacup.ai.worker.concurrency:4}") int concurrency,
            @Value("${teacup.ai.worker.shutdown-await-seconds:180}") int shutdownAwaitSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("ai-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(shutdownAwaitSeconds);
        executor.initialize();
        return executor;
    }

    @Bean(name = "aiRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory aiRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter aiMessageConverter,
            @Qualifier("aiWorkerExecutor") ThreadPoolTaskExecutor aiWorkerExecutor,
            @Value("${teacup.ai.worker.concurrency:4}") int concurrency) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(aiMessageConverter);
        factory.setTaskExecutor(aiWorkerExecutor);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setMissingQueuesFatal(true);
        return factory;
    }

    /**
     * 死信队列专用容器：单并发、不使用 aiWorkerExecutor。
     * 死信处理是低频收尾动作，不得占用工作线程池的 4 个任务槽位。
     */
    @Bean(name = "aiDlqListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory aiDlqListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter aiMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(aiMessageConverter);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setMissingQueuesFatal(true);
        return factory;
    }

    private static Queue retryQueue(String name, int ttlMillis) {
        return QueueBuilder.durable(name)
                .quorum()
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .ttl(ttlMillis)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(TASK_ROUTING_KEY)
                .build();
    }
}
