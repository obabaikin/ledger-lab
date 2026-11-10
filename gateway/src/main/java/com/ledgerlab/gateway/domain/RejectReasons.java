package com.ledgerlab.gateway.domain;

import com.ledgerlab.common.RejectReason;
import org.jspecify.annotations.Nullable;

public final class RejectReasons {

    public static final String REJECT_REASON_DESCRIPTION = "Set when status is REJECTED; null if the reason is newer than this API version.";

    private RejectReasons() {
    }

    /** The reason as the API shows it; a stored reason this gateway does not know yet reads as null (ADR 0011). */
    public static @Nullable RejectReason rejectReasonOf(String stored) {
        for (var reason : RejectReason.values()) {
            if (reason.name().equals(stored)) {
                return reason;
            }
        }
        return null;
    }
}
