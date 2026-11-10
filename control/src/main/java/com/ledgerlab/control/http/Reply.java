package com.ledgerlab.control.http;

import org.jspecify.annotations.Nullable;

/** What a service did with a call: the caller decides which codes are final for it. */
public sealed interface Reply<T> {

    int SERVER_ERROR = 500;

    record Answered<T>(int status, @Nullable T body) implements Reply<T> {
    }

    record Refused<T>(int status, String error) implements Reply<T> {
    }

    /** Timed out or cut off after connecting: the call may or may not have taken effect. */
    record NoAnswer<T>() implements Reply<T> {
    }

    /** The call never reached the service, so sending it again cannot repeat its effect. */
    record NotConnected<T>() implements Reply<T> {
    }

    /** A 2xx this client cannot read, e.g. from a newer service: it took effect, but tells nothing. */
    record Unreadable<T>() implements Reply<T> {
    }

    /** 5xx, no answer or an answer this client cannot read: the call is repeated on a later tick, within the phase's deadline. */
    default boolean isTransient() {
        return switch (this) {
            case NoAnswer<T> noAnswer -> true;
            case NotConnected<T> notConnected -> true;
            case Unreadable<T> unreadable -> true;
            case Refused<T> refused -> refused.status() >= SERVER_ERROR;
            case Answered<T> answered -> false;
        };
    }
}
