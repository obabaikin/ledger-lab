package com.ledgerlab.ledger

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class LedgerApplication

fun main(args: Array<String>) {
    runApplication<LedgerApplication>(*args)
}
