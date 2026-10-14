package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.model.*;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.RandomTokenPort;
import java.time.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class OidcAuthenticationService implements BeginOidcAuthenticationUseCase,ExchangeOidcTokenUseCase {
    private final CustomerAuthenticationPolicy policy;
    private final AuthenticationTransactionService transactions;
    private final AuthenticationAttemptStore attempts;
    private final IdentityTokenVerifierPort verifier;
    private final KeyedDigestPort digests;
    private final RandomTokenPort random;
    private final CustomerAuthenticationCompletionService completion;
    private final IdentityLinkProofService links;
    private final CustomerAuthenticationLifetimePort lifetimes;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CustomerTelemetryPort telemetry=CustomerTelemetryPort.NOOP;

    public OidcAuthenticationService(CustomerAuthenticationPolicy policy,AuthenticationTransactionService transactions,
            AuthenticationAttemptStore attempts,IdentityTokenVerifierPort verifier,KeyedDigestPort digests,
            RandomTokenPort random,CustomerAuthenticationCompletionService completion,IdentityLinkProofService links,
            CustomerAuthenticationLifetimePort lifetimes,Clock clock) {
        this.policy=policy; this.transactions=transactions; this.attempts=attempts; this.verifier=verifier;
        this.digests=digests; this.random=random; this.completion=completion; this.links=links;
        this.lifetimes=lifetimes; this.clock=clock;
    }

    @Override public BeginOidcAuthenticationUseCase.Result begin(BeginOidcAuthenticationUseCase.Command command) {
        AuthenticationIntent intent=CustomerAuthenticationSupport.intent(command.intent());
        return credentialPhase(() -> {
            var context=policy.load(command.siteKey(),command.providerKey(),command.origin(),CustomerAuthenticationMode.OIDC_TOKEN);
            Instant now=clock.instant(),expiry=now.plus(lifetimes.oidcAttemptTtl());
            String nonce=random.issue(32);
            String digest=CustomerAuthenticationSupport.reference(digests.digest(KeyedDigestPort.Purpose.OIDC_NONCE,nonce));
            var attempt=transactions.begin(context.site().key().value(),context.provider().providerKey(),context.origin(),intent,digest,now,expiry);
            return new BeginOidcAuthenticationUseCase.Result(attempt.id(),nonce,attempt.expiresAt());
        });
    }

    @Override public CustomerAuthenticationResult exchange(ExchangeOidcTokenUseCase.Command command) {
        Instant started=clock.instant();boolean succeeded=false;
        try {var result=authenticate(command);succeeded=true;return result;}
        finally {telemetry.authExchange(CustomerTelemetryPort.Mode.OIDC_TOKEN,
            succeeded?CustomerTelemetryPort.Outcome.SUCCESS:CustomerTelemetryPort.Outcome.REJECTED,Duration.between(started,clock.instant()));}
    }
    private CustomerAuthenticationResult authenticate(ExchangeOidcTokenUseCase.Command command) {
        AuthenticationIntent intent=CustomerAuthenticationSupport.intent(command.intent());
        Instant now=clock.instant();
        var verified=credentialPhase(() -> {
            var context=policy.load(command.siteKey(),command.providerKey(),command.origin(),CustomerAuthenticationMode.OIDC_TOKEN);
            var attempt=attempts.find(command.attemptId()).orElseThrow(CustomerAuthenticationSupport::failed);
            if(!attempt.siteId().equals(context.site().id()) || !attempt.providerKey().equals(context.provider().providerKey())
                || !attempt.origin().equals(context.origin()) || attempt.intent()!=intent
                || attempt.providerConfigVersion()!=context.provider().version() || !attempt.activeAt(now)
                || !digests.matches(KeyedDigestPort.Purpose.OIDC_NONCE,command.nonce(),digest(attempt.nonceDigest()))) {
                throw CustomerAuthenticationSupport.failed();
            }
            VerifiedIdentityEvidence evidence=verifier.verifyOidc(context.provider(),command.token(),command.nonce(),now);
            var consumed=transactions.consumeOidc(attempt.id(),command.siteKey(),command.providerKey(),context.origin(),intent,
                command.nonce(),context.provider().version(),digests);
            return new Verified(context,evidence,consumed.consumedAt());
        });
        try {
            if(!verified.context().provider().providerKey().equals(verified.evidence().issuerKey())) throw CustomerAuthenticationSupport.failed();
            return result(intent,verified,verified.consumedAt());
        } catch(RuntimeException failure) {
            throw CustomerAuthenticationSupport.failed();
        }
    }

    private CustomerAuthenticationResult result(AuthenticationIntent intent,Verified verified,Instant now) {
        if(intent==AuthenticationIntent.LINK) {
            return links.issue(verified.context().site().id(),verified.context().origin(),CustomerAuthenticationMode.OIDC_TOKEN,verified.evidence(),now);
        }
        return completion.authenticate(verified.context().site().id(),CustomerAuthenticationMode.OIDC_TOKEN,
            verified.evidence(),now,verified.context().origin());
    }

    private static KeyedDigestPort.DigestValue digest(String reference) {
        int separator=reference.indexOf(':');
        if(separator<1 || separator==reference.length()-1) throw CustomerAuthenticationSupport.failed();
        return new KeyedDigestPort.DigestValue(reference.substring(separator+1),reference.substring(0,separator));
    }
    private static <T> T credentialPhase(Supplier<T> phase) {
        try { return phase.get(); }
        catch(RuntimeException failure) { throw CustomerAuthenticationSupport.failed(); }
    }
    private record Verified(CustomerAuthenticationPolicy.Context context,VerifiedIdentityEvidence evidence,Instant consumedAt) {}
}
