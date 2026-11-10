package com.ledgerlab.generator;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * For a request field with a default: left out, it reads as null and the default applies; an explicit null is a
 * client's mistake and fails the request. Jackson's own null handling treats the two alike.
 */
abstract class OmittedOrValue<T> extends StdDeserializer<T> {

    private final Class<T> type;

    OmittedOrValue(Class<T> type) {
        super(type);
        this.type = type;
    }

    @Override
    public T deserialize(JsonParser parser, DeserializationContext context) {
        return context.readValue(parser, type);
    }

    @Override
    public T getNullValue(DeserializationContext context) {
        return context.reportInputMismatch(type, "null is not a value of this field; leave the field out for its default");
    }

    @Override
    public @Nullable Object getAbsentValue(DeserializationContext context) {
        return null;
    }

    static final class OfInteger extends OmittedOrValue<Integer> {
        OfInteger() {
            super(Integer.class);
        }
    }

    static final class OfDouble extends OmittedOrValue<Double> {
        OfDouble() {
            super(Double.class);
        }
    }
}
