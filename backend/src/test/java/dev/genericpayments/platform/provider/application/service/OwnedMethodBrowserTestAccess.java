package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.Pending;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import java.util.function.Function;

/** Hosted tests exercise the package-private same-service boundary without widening production visibility. */
public final class OwnedMethodBrowserTestAccess {
    private OwnedMethodBrowserTestAccess(){}
    public static <T>T phase(OwnedFailedRenewalMethodService service,Reservation reservation,SubscriptionCommandContext context,Function<Pending,T> work){
        return service.browserPhase(reservation,context,work);
    }
}
