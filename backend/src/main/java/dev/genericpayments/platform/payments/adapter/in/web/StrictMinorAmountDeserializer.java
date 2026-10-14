package dev.genericpayments.platform.payments.adapter.in.web;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Minor units are integer JSON numbers; truncation and string coercion are forbidden. */
public final class StrictMinorAmountDeserializer extends ValueDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
            return context.reportInputMismatch(Long.class, "Minor amount must be an integer");
        return parser.getLongValue();
    }
}
