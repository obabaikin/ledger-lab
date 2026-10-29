package com.ledgerlab.control.entry

import com.ledgerlab.control.ControlProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

class TokenRequiredException : RuntimeException()

class UnknownLinkException : RuntimeException()

@Service
class AddressDeletionService(private val mails: InviteMailRepository, private val props: ControlProperties, private val tx: TransactionTemplate) {

    fun request(token: String?): Deletion {
        if (token.isNullOrBlank()) throw TokenRequiredException()
        return tx.execute {
            mails.lock()
            mails.requestDeletion(token, mails.now() + props.invites.email.window)
        } ?: throw UnknownLinkException()
    }
}
