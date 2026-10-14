package dev.genericpayments.platform.payments.adapter.in.web;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** The Partner contract admits JSON strings, never scalar-to-string coercion. */
public final class StrictMetadataStringDeserializer extends ValueDeserializer<String> {
    @Override public String deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING))
            return context.reportInputMismatch(String.class, "Metadata values must be strings");
        return parser.getString();
    }
}
