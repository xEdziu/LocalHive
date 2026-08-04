package dev.adrian.goral.localhivebackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AdminExecutionGroupSseConfig {

    @Bean(name = "adminExecutionGroupSseExecutor")
    public ThreadPoolTaskExecutor adminExecutionGroupSseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(1);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("admin-group-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
