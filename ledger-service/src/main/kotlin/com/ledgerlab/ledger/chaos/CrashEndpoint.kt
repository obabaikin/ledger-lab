package com.ledgerlab.ledger.chaos

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.time.Duration

@ConfigurationProperties("ledger.chaos")
data class ChaosProperties(val enabled: Boolean, val haltDelay: Duration)

fun interface Halter {
    fun halt()
}

@Endpoint(id = CrashEndpoint.ID)
class CrashEndpoint(private val halter: Halter, private val haltDelay: Duration) {

    @WriteOperation
    fun crash(): WebEndpointResponse<Unit> {
        // The answer must leave first: a connection cut by the halt would read to control as an instance that did not crash.
        Thread.ofVirtual().start {
            Thread.sleep(haltDelay)
            halter.halt()
        }
        return WebEndpointResponse(HttpStatus.ACCEPTED.value())
    }

    companion object {
        const val ID = "crash"
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("ledger.chaos.enabled")
class ChaosConfig {

    // halt, not exit: no shutdown hooks, so no offset commit and no LeaveGroup, as after kill -9.
    @Bean
    @ConditionalOnMissingBean
    fun halter(): Halter = Halter { Runtime.getRuntime().halt(CRASH_EXIT_CODE) }

    @Bean
    fun crashEndpoint(halter: Halter, props: ChaosProperties) = CrashEndpoint(halter, props.haltDelay)

    private companion object {
        const val CRASH_EXIT_CODE = 1
    }
}
