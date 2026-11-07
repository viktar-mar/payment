package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.Pending;
import java.time.Instant;

/** One original pending SetupIntent SDK retrieve outside persistence transactions, with no
 * application/SDK retries. Existing transparent transport recovery shares its single budget. */
public interface OwnedMethodBrowserReader {
    EphemeralSetup read(Pending pending,Instant deadline);
    record EphemeralSetup(String setupReference,String publishableKey,String clientSecret) {
        @Override public String toString(){return "OwnedMethodBrowser.EphemeralSetup[redacted]";}
    }
}
