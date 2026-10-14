package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class EmailOtpService implements RequestEmailOtpUseCase,VerifyEmailOtpUseCase {
    private final CustomerAuthenticationPolicy policy; private final EmailNormalizationPort emails; private final KeyedDigestPort digests;
    private final DistributedCustomerRateLimitPort rateLimits; private final EmailOtpCreationTransaction creation;
    private final EmailOtpVerificationTransaction verification; private final CustomerMailCapability capability;
    private final CustomerMailTemplatePort templates; private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CustomerTelemetryPort telemetry=CustomerTelemetryPort.NOOP;
    public EmailOtpService(CustomerAuthenticationPolicy policy,EmailNormalizationPort emails,KeyedDigestPort digests,
            DistributedCustomerRateLimitPort rateLimits,EmailOtpCreationTransaction creation,EmailOtpVerificationTransaction verification,
            CustomerMailCapability capability,CustomerMailTemplatePort templates,Clock clock) {
        this.policy=policy;this.emails=emails;this.digests=digests;this.rateLimits=rateLimits;this.creation=creation;
        this.verification=verification;this.capability=capability;this.templates=templates;this.clock=clock;
    }
    @Override public RequestEmailOtpUseCase.Result request(RequestEmailOtpUseCase.Command command) {
        CustomerTelemetryPort.OtpOutcome outcome=CustomerTelemetryPort.OtpOutcome.REJECTED;
        try { var result=requestChallenge(command);outcome=CustomerTelemetryPort.OtpOutcome.REQUESTED;return result; }
        catch(PaymentApplicationException failure) {
            outcome=switch(failure.code()) {
                case "OTP_RATE_LIMITED" -> CustomerTelemetryPort.OtpOutcome.RATE_LIMITED;
                case "AUTHENTICATION_MODE_UNAVAILABLE" -> CustomerTelemetryPort.OtpOutcome.UNAVAILABLE;
                default -> CustomerTelemetryPort.OtpOutcome.REJECTED;
            };throw failure;
        } finally {telemetry.otp(outcome);}
    }
    private RequestEmailOtpUseCase.Result requestChallenge(RequestEmailOtpUseCase.Command command) {
        Objects.requireNonNull(command,"command");
        try {
            AuthenticationIntent intent=CustomerAuthenticationSupport.intent(command.intent());
            var context=policy.loadEmail(command.siteKey(),command.origin());
            String normalized=emails.normalize(command.email());
            String source=canonicalSource(command.sourceAddress());
            var emailDigests=digests.candidates(KeyedDigestPort.Purpose.EMAIL_LOOKUP,normalized).stream()
                .map(CustomerAuthenticationSupport::reference).distinct().toList();
            rateLimits.acquire(context.site().id(),digests.digest(KeyedDigestPort.Purpose.OTP_SITE_BUCKET,context.site().id().value().toString()),
                digests.digest(KeyedDigestPort.Purpose.OTP_EMAIL_BUCKET,normalized),digests.digest(KeyedDigestPort.Purpose.SOURCE_IP,source),clock.instant());
            var config=(IdentityProviderConfiguration.EmailOtpConfiguration)context.provider().configuration();
            if(!capability.available(config.senderRef())||!templates.available(config.templateKey())) throw unavailable();
            return creation.create(context.site().id(),context.origin(),intent,normalized,emailDigests,context.provider().providerKey(),
                config.senderRef(),config.templateKey(),clock.instant());
        } catch(PaymentApplicationException failure){throw failure;}
        catch(RuntimeException failure){throw CustomerAuthenticationSupport.failed();}
    }
    @Override public CustomerAuthenticationResult verify(VerifyEmailOtpUseCase.Command command) {
        var started=clock.instant();boolean succeeded=false;
        try {var result=verifyChallenge(command);succeeded=true;return result;}
        finally {
            telemetry.otp(succeeded?CustomerTelemetryPort.OtpOutcome.VERIFIED:CustomerTelemetryPort.OtpOutcome.REJECTED);
            telemetry.authExchange(CustomerTelemetryPort.Mode.EMAIL_OTP,succeeded?CustomerTelemetryPort.Outcome.SUCCESS:CustomerTelemetryPort.Outcome.REJECTED,
                Duration.between(started,clock.instant()));
        }
    }
    private CustomerAuthenticationResult verifyChallenge(VerifyEmailOtpUseCase.Command command) {
        Objects.requireNonNull(command,"command");
        try {
            var context=policy.loadEmailSite(command.siteKey(),command.origin());
            var outcome=verification.verify(context.site().id(),context.origin(),
                new EmailOtpChallengeId(command.challengeId()),command.code());
            if(!outcome.succeeded()) throw invalid();
            return outcome.result();
        } catch(PaymentApplicationException failure) {
            if("OTP_INVALID_OR_EXPIRED".equals(failure.code())) throw failure;
            throw invalid();
        } catch(RuntimeException failure){throw invalid();}
    }
    private static String canonicalSource(String source) {
        if(source==null||source.isBlank()||source.length()>64||!source.matches("[0-9A-Fa-f:.]+"))
            throw CustomerAuthenticationSupport.failed();
        try{return java.net.InetAddress.getByName(source).getHostAddress();}
        catch(java.net.UnknownHostException invalid){throw CustomerAuthenticationSupport.failed();}
    }
    private static PaymentApplicationException invalid(){return new PaymentApplicationException("OTP_INVALID_OR_EXPIRED",401,"OTP is invalid or expired");}
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("AUTHENTICATION_MODE_UNAVAILABLE",503,"Authentication mode unavailable");}
}
