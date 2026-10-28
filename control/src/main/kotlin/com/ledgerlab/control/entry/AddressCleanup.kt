package com.ledgerlab.control.entry

import com.ledgerlab.control.ControlProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** Deletes under the requests' lock: a request in between would lose its address, or fail on it with the address in the log. */
@Component
class AddressCleanup(private val mails: InviteMailRepository, private val props: ControlProperties, private val tx: TransactionTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${control.cleanup.interval}", initialDelayString = "\${control.cleanup.interval}")
    fun deleteUnused(): Int {
        val deleted = locked { mails.deleteUnusedSince(mails.now() - props.invites.email.retention) }
        if (deleted > 0) log.info("Deleted {} addresses unused for {}", deleted, props.invites.email.retention)
        return deleted
    }

    @Scheduled(fixedDelayString = "\${control.invites.email.deletion-check}", initialDelayString = "\${control.invites.email.deletion-check}")
    fun deleteRequested(): Int {
        val deleted = locked { mails.deleteDue() }
        if (deleted > 0) log.info("Deleted {} addresses on request", deleted)
        return deleted
    }

    private fun locked(delete: () -> Int): Int = checkNotNull(
        tx.execute {
            mails.lock()
            delete()
        },
    )
}
