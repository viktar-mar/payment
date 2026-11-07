package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.Pending;
import java.time.Instant;

/** Exactly one original pending SetupIntent read, outside all persistence transactions. */
public interface OwnedMethodBrowserReader {
    EphemeralSetup read(Pending pending,Instant deadline);
    record EphemeralSetup(String setupReference,String publishableKey,String clientSecret) {
        @Override public String toString(){return "OwnedMethodBrowser.EphemeralSetup[redacted]";}
    }
}
