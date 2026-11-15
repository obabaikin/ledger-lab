package com.ledgerlab.ledger;

import org.springframework.boot.task.SimpleAsyncTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.TaskSchedulerRouter;

@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {

    public static final String RESULT_PUBLISHER_SCHEDULER = "resultPublisherScheduler";
    public static final String CREDIT_APPLIER_SCHEDULER = "creditApplierScheduler";

    private static final String RESULT_PUBLISHER_THREAD = "result-publisher-";
    private static final String CREDIT_APPLIER_THREAD = "credit-applier-";

    /** Boot drops its default scheduler once any other is declared; the jobs without their own still run here. */
    @Bean(name = TaskSchedulerRouter.DEFAULT_TASK_SCHEDULER_BEAN_NAME)
    public SimpleAsyncTaskScheduler taskScheduler(SimpleAsyncTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean(name = RESULT_PUBLISHER_SCHEDULER)
    public ThreadPoolTaskScheduler resultPublisherScheduler() {
        return singleThread(RESULT_PUBLISHER_THREAD);
    }

    @Bean(name = CREDIT_APPLIER_SCHEDULER)
    public ThreadPoolTaskScheduler creditApplierScheduler() {
        return singleThread(CREDIT_APPLIER_THREAD);
    }

    private static ThreadPoolTaskScheduler singleThread(String threadNamePrefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        return scheduler;
    }
}
