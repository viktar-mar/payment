package dev.genericpayments.platform.customers.application.model;

import dev.genericpayments.platform.customers.domain.*;
import java.time.Instant;
import java.util.*;

/** Raw bearer material is exposed only by this one-shot application boundary. */
public record CustomerSessionCreation(String rawToken,CustomerSessionContext context) {
    public CustomerSessionCreation {
        if(rawToken==null || rawToken.isBlank()) throw new IllegalArgumentException("session token is required");
        Objects.requireNonNull(context,"session context");
    }
    public CustomerSessionId sessionId(){return context.sessionId();}
    public Instant expiresAt(){return context.expiresAt();}
    public Set<CustomerSessionScope> scopes(){return context.scopes();}
    @Override public String toString(){return "CustomerSessionCreation[sessionId="+sessionId()+", token=redacted]";}
}
