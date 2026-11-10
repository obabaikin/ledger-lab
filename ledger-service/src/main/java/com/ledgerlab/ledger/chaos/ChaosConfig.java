package com.ledgerlab.ledger.chaos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("ledger.chaos.enabled")
public class ChaosConfig {

    private static final int CRASH_EXIT_CODE = 1;

    // halt, not exit: no shutdown hooks, so no offset commit and no LeaveGroup, as after kill -9.
    @Bean
    @ConditionalOnMissingBean
    public Halter halter() {
        return () -> Runtime.getRuntime().halt(CRASH_EXIT_CODE);
    }

    @Bean
    public CrashEndpoint crashEndpoint(Halter halter, ChaosProperties props) {
        return new CrashEndpoint(halter, props.haltDelay());
    }
}
