package com.ledgerlab.gateway;

import org.springframework.boot.task.SimpleAsyncTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.TaskSchedulerRouter;

@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {

    public static final String OUTBOX_PUBLISHER_SCHEDULER = "outboxPublisherScheduler";

    private static final String OUTBOX_PUBLISHER_THREAD = "outbox-publisher-";

    /** Boot drops its default scheduler once any other is declared; the jobs without their own still run here. */
    @Bean(name = TaskSchedulerRouter.DEFAULT_TASK_SCHEDULER_BEAN_NAME)
    public SimpleAsyncTaskScheduler taskScheduler(SimpleAsyncTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean(name = OUTBOX_PUBLISHER_SCHEDULER)
    public ThreadPoolTaskScheduler outboxPublisherScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(OUTBOX_PUBLISHER_THREAD);
        return scheduler;
    }
}
