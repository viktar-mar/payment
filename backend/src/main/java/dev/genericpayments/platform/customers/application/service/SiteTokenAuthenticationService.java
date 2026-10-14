package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.model.*;
import dev.genericpayments.platform.customers.application.port.in.ExchangeSiteTokenUseCase;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class SiteTokenAuthenticationService implements ExchangeSiteTokenUseCase {
    private final CustomerAuthenticationPolicy policy;
    private final AuthenticationTransactionService transactions;
    private final IdentityTokenVerifierPort verifier;
    private final KeyedDigestPort digests;
    private final CustomerAuthenticationCompletionService completion;
    private final IdentityLinkProofService links;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CustomerTelemetryPort telemetry=CustomerTelemetryPort.NOOP;

    public SiteTokenAuthenticationService(CustomerAuthenticationPolicy policy,AuthenticationTransactionService transactions,
            IdentityTokenVerifierPort verifier,KeyedDigestPort digests,CustomerAuthenticationCompletionService completion,
            IdentityLinkProofService links,Clock clock) {
        this.policy=policy; this.transactions=transactions; this.verifier=verifier; this.digests=digests;
        this.completion=completion; this.links=links; this.clock=clock;
    }

    @Override public CustomerAuthenticationResult exchange(ExchangeSiteTokenUseCase.Command command) {
        Instant started=clock.instant();
        var outcome=CustomerTelemetryPort.Outcome.REJECTED;
        try {
            var result=authenticate(command);
            outcome=CustomerTelemetryPort.Outcome.SUCCESS;
            return result;
        } finally {
            telemetry.authExchange(CustomerTelemetryPort.Mode.SITE_TOKEN,outcome,Duration.between(started,clock.instant()));
        }
    }
    private CustomerAuthenticationResult authenticate(ExchangeSiteTokenUseCase.Command command) {
        AuthenticationIntent intent=CustomerAuthenticationSupport.intent(command.intent());
        Instant now=clock.instant();
        var verified=credentialPhase(() -> {
            var context=policy.load(command.siteKey(),command.providerKey(),command.origin(),CustomerAuthenticationMode.SITE_TOKEN);
            VerifiedIdentityEvidence evidence=verifier.verifySiteToken(context.provider(),command.token(),now);
            String tokenId=evidence.tokenId().orElseThrow(CustomerAuthenticationSupport::failed);
            Instant expiry=evidence.expiresAt().plusSeconds(60);
            if(!expiry.isAfter(now)) expiry=now.plusSeconds(1);
            Instant retainedUntil=expiry;
            List<ConsumedSiteTokenJti> consumed=digests.candidates(KeyedDigestPort.Purpose.SITE_TOKEN_JTI,tokenId).stream()
                .map(digest -> new ConsumedSiteTokenJti(UUID.randomUUID(),context.site().id(),context.provider().providerKey(),
                    CustomerAuthenticationSupport.reference(digest),now,retainedUntil,0))
                .toList();
            transactions.consumeSiteToken(command.siteKey(),command.providerKey(),context.origin(),context.provider().version(),consumed);
            return new Verified(context,evidence);
        });
        try {
            if(!verified.context().provider().providerKey().equals(verified.evidence().issuerKey())) throw CustomerAuthenticationSupport.failed();
            if(intent==AuthenticationIntent.LINK) {
                return links.issue(verified.context().site().id(),verified.context().origin(),CustomerAuthenticationMode.SITE_TOKEN,verified.evidence(),now);
            }
            return completion.authenticate(verified.context().site().id(),CustomerAuthenticationMode.SITE_TOKEN,
                verified.evidence(),now,verified.context().origin());
        } catch(RuntimeException failure) {
            throw CustomerAuthenticationSupport.failed();
        }
    }

    private static <T> T credentialPhase(Supplier<T> phase) {
        try { return phase.get(); }
        catch(RuntimeException failure) { throw CustomerAuthenticationSupport.failed(); }
    }
    private record Verified(CustomerAuthenticationPolicy.Context context,VerifiedIdentityEvidence evidence) {}
}
