package dev.genericpayments.platform.subscriptions.adapter.in.web;
import com.fasterxml.jackson.annotation.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.SubscriptionConfirmationUseCase;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
@RestController
@RequestMapping(value="/api/v1/customer/subscriptions/{id}/operations/{operationId}",produces=MediaType.APPLICATION_JSON_VALUE)
public class SubscriptionConfirmationController {
 private final SubscriptionConfirmationUseCase use;
 public SubscriptionConfirmationController(SubscriptionConfirmationUseCase use){this.use=use;}
 @io.swagger.v3.oas.annotations.media.Schema(name="BillingConfirmationSelectionRequest")
 public record Selection(@JsonProperty("action_token") String actionToken){@Override public String toString(){return "Selection[redacted]";}}
 @io.swagger.v3.oas.annotations.media.Schema(name="BillingConfirmationRequest")
 public record Confirmation(String capability,@JsonProperty("confirmation_token") String confirmationToken){@Override public String toString(){return "Confirmation[redacted]";}}
 @io.swagger.v3.oas.annotations.media.Schema(name="BillingConfirmationEmptyRequest")
 public record Empty() {}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 @io.swagger.v3.oas.annotations.media.Schema(name="BillingConfirmationSessionResponse")
 public record SessionResponse(String protocol,String provider,SubscriptionClientSession.Flow flow,@JsonProperty("publishable_key") String publishableKey,@JsonProperty("client_secret") String clientSecret,@JsonProperty("action_token") String actionToken,@JsonProperty("attempt_id") UUID attemptId,String capability,@JsonProperty("expires_at") Instant expiresAt){@Override public String toString(){return "ConfirmationSessionResponse[redacted]";}}
 @io.swagger.v3.oas.annotations.media.Schema(name="BillingConfirmationStatusResponse")
 public record StatusResponse(String protocol,@JsonProperty("attempt_id") UUID attemptId,SubscriptionConfirmationAttempt.State state,@JsonProperty("retry_allowed") boolean retryAllowed,@JsonProperty("successor_attempt_id") UUID successorAttemptId,@JsonProperty("expires_at") Instant expiresAt) {}
 @io.swagger.v3.oas.annotations.media.Schema(name="BillingConfirmationCommandResponse")
 public record ConfirmResponse(String protocol,@JsonProperty("attempt_id") UUID attemptId,SubscriptionConfirmationAttempt.State state,@JsonProperty("retry_allowed") boolean retryAllowed,@JsonProperty("successor_attempt_id") UUID successorAttemptId,@JsonProperty("expires_at") Instant expiresAt,@JsonProperty("next_action_allowed") boolean nextActionAllowed) {}
 @PostMapping("/confirmation-session") public ResponseEntity<SessionResponse> select(@PathVariable UUID id,@PathVariable UUID operationId,@RequestBody Selection body,HttpServletRequest request){
  if(body.actionToken()==null||!body.actionToken().matches("bga1_[A-Za-z0-9_-]{43}"))throw AbstractSubscriptionController.invalid();return ok(session(use.select(scope(id,operationId,request),body.actionToken())));
 }
 @PostMapping("/confirmation-attempts/{attemptId}/client-session") public ResponseEntity<SessionResponse> session(@PathVariable UUID id,@PathVariable UUID operationId,@PathVariable UUID attemptId,@RequestBody Empty body,HttpServletRequest request){return ok(session(use.session(scope(id,operationId,request),attemptId)));}
 @GetMapping("/confirmation-attempts/current") public ResponseEntity<Object> current(@PathVariable UUID id,@PathVariable UUID operationId,HttpServletRequest request){var result=use.current(scope(id,operationId,request));return ok(result==null?"null":status(result));}
 @GetMapping("/confirmation-attempts/{attemptId}") public ResponseEntity<StatusResponse> status(@PathVariable UUID id,@PathVariable UUID operationId,@PathVariable UUID attemptId,HttpServletRequest request){return ok(status(use.status(scope(id,operationId,request),attemptId)));}
 @PostMapping("/confirmation-attempts/{attemptId}/confirm") public ResponseEntity<ConfirmResponse> confirm(@PathVariable UUID id,@PathVariable UUID operationId,@PathVariable UUID attemptId,@RequestBody Confirmation body,HttpServletRequest request){
  if(body.capability()==null||!body.capability().matches("bca2_[A-Za-z0-9_-]{43}")||body.confirmationToken()==null||!body.confirmationToken().matches("ctoken_[A-Za-z0-9]{1,200}"))throw AbstractSubscriptionController.invalid();
  var r=use.confirm(scope(id,operationId,request),attemptId,body.capability(),body.confirmationToken());var v=r.status();return ok(new ConfirmResponse("SERVER_CONFIRM_V2",v.attemptId(),v.state(),v.retryAllowed(),v.successorAttemptId(),v.expiresAt(),r.nextActionAllowed()));
 }
 @PostMapping("/confirmation-attempts/{attemptId}/retry") public ResponseEntity<StatusResponse> retry(@PathVariable UUID id,@PathVariable UUID operationId,@PathVariable UUID attemptId,@RequestBody Empty body,HttpServletRequest request){return ok(status(use.retry(scope(id,operationId,request),attemptId)));}
 private static SessionResponse session(SubscriptionConfirmationUseCase.Session r){return new SessionResponse(r.protocol(),r.provider(),r.flow(),r.publishableKey(),r.clientSecret(),r.actionToken(),r.attemptId(),r.capability(),r.expiresAt());}
 private static StatusResponse status(SubscriptionConfirmationUseCase.Status r){return new StatusResponse("SERVER_CONFIRM_V2",r.attemptId(),r.state(),r.retryAllowed(),r.successorAttemptId(),r.expiresAt());}
 private static SubscriptionConfirmationUseCase.Scope scope(UUID id,UUID operation,HttpServletRequest r){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!(auth.getPrincipal() instanceof CustomerSessionContext s))throw AbstractSubscriptionController.invalid();return new SubscriptionConfirmationUseCase.Scope(new SubscriptionCommandContext(new SubscriptionActor.Customer(s),s.siteId(),s.customerId(),s.origin(),r.getHeader("Idempotency-Key"),RequestIdFilter.requestId(r)),id,operation);}
 private static <T>ResponseEntity<T> ok(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(value);}
}
