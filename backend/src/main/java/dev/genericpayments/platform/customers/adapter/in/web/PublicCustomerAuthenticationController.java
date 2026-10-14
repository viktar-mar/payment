package dev.genericpayments.platform.customers.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.genericpayments.platform.customers.application.model.*;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/v1/public/customer-auth",produces=MediaType.APPLICATION_JSON_VALUE)
public class PublicCustomerAuthenticationController {
    private final ExchangeSiteTokenUseCase siteTokens;
    private final BeginOidcAuthenticationUseCase oidcBegins;
    private final ExchangeOidcTokenUseCase oidcTokens;
    private final CustomerSessionService sessions;
    private final RequestEmailOtpUseCase emailOtpRequests;
    private final VerifyEmailOtpUseCase emailOtpVerifications;
    private final CustomerSourceAddressResolver sourceAddresses;
    public PublicCustomerAuthenticationController(ExchangeSiteTokenUseCase siteTokens,BeginOidcAuthenticationUseCase oidcBegins,
            ExchangeOidcTokenUseCase oidcTokens,CustomerSessionService sessions,RequestEmailOtpUseCase emailOtpRequests,
            VerifyEmailOtpUseCase emailOtpVerifications,CustomerSourceAddressResolver sourceAddresses) {
        this.siteTokens=siteTokens; this.oidcBegins=oidcBegins; this.oidcTokens=oidcTokens; this.sessions=sessions;
        this.emailOtpRequests=emailOtpRequests;this.emailOtpVerifications=emailOtpVerifications;this.sourceAddresses=sourceAddresses;
    }

    @PostMapping("/site-token/exchanges") @Operation(operationId="exchangeCustomerSiteToken")
    public SessionResponse exchangeSiteToken(@RequestBody SiteTokenRequest body,HttpServletRequest request) {
        String origin=CustomerSessionAuthenticationFilterOrigin.origin(request);
        var result=siteTokens.exchange(new ExchangeSiteTokenUseCase.Command(body.siteKey(),body.providerKey(),origin,
            AuthenticationIntent.AUTHENTICATE,body.token()));
        return session(result);
    }

    @PostMapping("/oidc/attempts") @ResponseStatus(HttpStatus.CREATED) @Operation(operationId="beginCustomerOidcAttempt")
    public OidcAttemptResponse beginOidc(@RequestBody OidcBeginRequest body,HttpServletRequest request) {
        var result=oidcBegins.begin(new BeginOidcAuthenticationUseCase.Command(body.siteKey(),body.providerKey(),
            CustomerSessionAuthenticationFilterOrigin.origin(request),body.intent()));
        return new OidcAttemptResponse(result.attemptId(),result.nonce(),result.expiresAt());
    }

    @PostMapping("/oidc/exchanges") @Operation(operationId="exchangeCustomerOidcToken",
        responses=@ApiResponse(responseCode="200",content=@Content(schema=@Schema(oneOf={SessionResponse.class,LinkProofResponse.class}))))
    public AuthenticationResponse exchangeOidc(@RequestBody OidcExchangeRequest body,HttpServletRequest request) {
        String origin=CustomerSessionAuthenticationFilterOrigin.origin(request);
        var result=oidcTokens.exchange(new ExchangeOidcTokenUseCase.Command(body.attemptId(),body.siteKey(),body.providerKey(),origin,
            body.intent(),body.nonce(),body.token()));
        if(result instanceof CustomerAuthenticationResult.AuthenticatedCustomer) return session(result);
        if(result instanceof CustomerAuthenticationResult.LinkProofIssued proof) return new LinkProofResponse(proof.proof(),proof.expiresAt());
        throw failed();
    }

    @PostMapping("/site-token/link-proofs") @Operation(operationId="createSiteTokenIdentityLinkProof")
    public LinkProofResponse linkSiteToken(@RequestBody SiteTokenRequest body,HttpServletRequest request) {
        String origin=CustomerSessionAuthenticationFilterOrigin.origin(request);
        var result=siteTokens.exchange(new ExchangeSiteTokenUseCase.Command(body.siteKey(),body.providerKey(),origin,
            AuthenticationIntent.LINK,body.token()));
        if(result instanceof CustomerAuthenticationResult.LinkProofIssued proof) return new LinkProofResponse(proof.proof(),proof.expiresAt());
        throw failed();
    }

    @PostMapping("/email-otp/challenges") @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId="requestCustomerEmailOtp")
    public EmailOtpChallengeResponse requestEmailOtp(@RequestBody EmailOtpRequest body,HttpServletRequest request) {
        var result=emailOtpRequests.request(new RequestEmailOtpUseCase.Command(body.siteKey(),
            CustomerSessionAuthenticationFilterOrigin.origin(request),body.intent(),body.email(),sourceAddresses.resolve(request)));
        return new EmailOtpChallengeResponse(result.challengeId(),result.expiresAt());
    }

    @PostMapping("/email-otp/challenges/{challengeId}/verify")
    @Operation(operationId="verifyCustomerEmailOtp",
        responses=@ApiResponse(responseCode="200",content=@Content(schema=@Schema(oneOf={SessionResponse.class,LinkProofResponse.class}))))
    public AuthenticationResponse verifyEmailOtp(@PathVariable UUID challengeId,@RequestBody EmailOtpVerifyRequest body,HttpServletRequest request) {
        var result=emailOtpVerifications.verify(new VerifyEmailOtpUseCase.Command(challengeId,body.siteKey(),
            CustomerSessionAuthenticationFilterOrigin.origin(request),body.code()));
        if(result instanceof CustomerAuthenticationResult.AuthenticatedCustomer)return session(result);
        if(result instanceof CustomerAuthenticationResult.LinkProofIssued proof)return new LinkProofResponse(proof.proof(),proof.expiresAt());
        throw failed();
    }

    private SessionResponse session(CustomerAuthenticationResult result) {
        if(result instanceof CustomerAuthenticationResult.AuthenticatedCustomer authenticated) {
            CustomerSessionCreation created=authenticated.session();
            UUID siteId=created.scopes().contains(CustomerSessionScope.SUBSCRIPTION_READ)||created.scopes().contains(CustomerSessionScope.SUBSCRIPTION_MANAGE)
                ?created.context().siteId().value():null;
            return new SessionResponse(created.rawToken(),created.expiresAt(),created.scopes(),customer(sessions.get(created.context())),siteId);
        }
        throw failed();
    }
    private static CustomerResponse customer(GetCurrentCustomerUseCase.CurrentCustomer customer) {
        return new CustomerResponse(customer.id().value(),customer.status(),customer.maskedEmail().orElse(null));
    }
    private static dev.genericpayments.platform.shared.application.PaymentApplicationException failed() {
        return new dev.genericpayments.platform.shared.application.PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");
    }

    @Schema(name="CustomerSiteTokenRequest")
    public record SiteTokenRequest(@Schema(name="site_key") String siteKey,@Schema(name="provider_key") String providerKey,
        @Schema(name="token",accessMode=Schema.AccessMode.WRITE_ONLY) String token) {
        @Override public String toString(){return "SiteTokenRequest[credentials=redacted]";}
    }
    @Schema(name="CustomerOidcBeginRequest")
    public record OidcBeginRequest(@Schema(name="site_key") String siteKey,@Schema(name="provider_key") String providerKey,
        AuthenticationIntent intent) {}
    @Schema(name="CustomerOidcExchangeRequest")
    public record OidcExchangeRequest(@Schema(name="attempt_id") UUID attemptId,@Schema(name="site_key") String siteKey,
        @Schema(name="provider_key") String providerKey,AuthenticationIntent intent,
        @Schema(name="nonce",accessMode=Schema.AccessMode.WRITE_ONLY) String nonce,
        @Schema(name="token",accessMode=Schema.AccessMode.WRITE_ONLY) String token) {
        @Override public String toString(){return "OidcExchangeRequest[attemptId="+attemptId+", credentials=redacted]";}
    }
    @Schema(name="CustomerOidcAttemptResponse")
    public record OidcAttemptResponse(@Schema(name="attempt_id") UUID attemptId,
        @Schema(name="nonce",accessMode=Schema.AccessMode.READ_ONLY) String nonce,@Schema(name="expires_at") Instant expiresAt) {
        @Override public String toString(){return "OidcAttemptResponse[attemptId="+attemptId+", nonce=redacted]";}
    }
    @Schema(name="CustomerEmailOtpRequest")
    public record EmailOtpRequest(@Schema(name="site_key") String siteKey,
        @Schema(name="email",accessMode=Schema.AccessMode.WRITE_ONLY) String email,AuthenticationIntent intent) {
        @Override public String toString(){return "EmailOtpRequest[credentials=redacted]";}
    }
    @Schema(name="CustomerEmailOtpVerifyRequest")
    public record EmailOtpVerifyRequest(@Schema(name="site_key") String siteKey,
        @Schema(name="code",accessMode=Schema.AccessMode.WRITE_ONLY) String code) {
        @Override public String toString(){return "EmailOtpVerifyRequest[credentials=redacted]";}
    }
    @Schema(name="CustomerEmailOtpChallengeResponse")
    public record EmailOtpChallengeResponse(@Schema(name="challenge_id") UUID challengeId,
        @Schema(name="expires_at") Instant expiresAt) {}
    @Schema(name="CustomerAuthenticationResponse",oneOf={SessionResponse.class,LinkProofResponse.class})
    public sealed interface AuthenticationResponse permits SessionResponse,LinkProofResponse {}
    @Schema(name="CustomerAuthenticationSessionResponse")
    public record SessionResponse(@Schema(name="session_token",accessMode=Schema.AccessMode.READ_ONLY) String sessionToken,
        @Schema(name="expires_at") Instant expiresAt,Set<CustomerSessionScope> scopes,CustomerResponse customer,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(name="site_id",type="string",format="uuid",requiredMode=Schema.RequiredMode.NOT_REQUIRED,
            description="Authenticated Site identity, present only for subscription-scoped sessions") UUID siteId) implements AuthenticationResponse {
        @Override public String toString(){return "SessionResponse[token=redacted, expiresAt="+expiresAt+"]";}
    }
    @Schema(name="CustomerAuthenticationLinkProofResponse")
    public record LinkProofResponse(@Schema(name="proof_token",accessMode=Schema.AccessMode.READ_ONLY) String proofToken,
        @Schema(name="expires_at") Instant expiresAt) implements AuthenticationResponse {
        @Override public String toString(){return "LinkProofResponse[proof=redacted, expiresAt="+expiresAt+"]";}
    }
    @Schema(name="CustomerAuthenticationCustomerResponse")
    public record CustomerResponse(UUID id,CustomerStatus status,@Schema(name="masked_email") String maskedEmail) {}
}
