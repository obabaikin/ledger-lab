package com.ledgerlab.gateway.service;

/** Which limit refused a submission with 503 busy; the tag of the refused count. */
public enum RefusalReason {
    INFLIGHT("inflight"),
    ADMISSION("admission");

    public static final String TAG = "reason";

    private final String tagValue;

    RefusalReason(String tagValue) {
        this.tagValue = tagValue;
    }

    public String tagValue() {
        return tagValue;
    }
}
