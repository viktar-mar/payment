package dev.genericpayments.platform.subscriptions.adapter.in.web;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionClientSessionUseCase;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;import java.util.UUID;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.security.core.context.SecurityContextHolder;
/** Raw provider material has exactly this CustomerSession-only, no-store response boundary. */
@RestController
public final class SubscriptionClientSessionController {
 private final CreateSubscriptionClientSessionUseCase use;
 public SubscriptionClientSessionController(CreateSubscriptionClientSessionUseCase use){this.use=use;}
 @io.swagger.v3.oas.annotations.media.Schema(name="SubscriptionClientSessionRequest")
 public record Request(@JsonProperty("action_token") String actionToken){@Override public String toString(){return "SubscriptionClientSessionRequest[redacted]";}}
 @io.swagger.v3.oas.annotations.media.Schema(name="SubscriptionClientSessionResponse")
 public record Response(String provider,SubscriptionClientSession.Flow flow,@JsonProperty("publishable_key") String publishableKey,@JsonProperty("client_secret") String clientSecret,@JsonProperty("action_token") String actionToken,@JsonProperty("expires_at") Instant expiresAt){@Override public String toString(){return "SubscriptionClientSessionResponse[redacted]";}}
 @PostMapping("/api/v1/customer/subscriptions/{id}/operations/{operationId}/client-session")
 public ResponseEntity<Response> issue(@PathVariable UUID id,@PathVariable UUID operationId,@RequestBody Request body,HttpServletRequest request){
  var authentication=SecurityContextHolder.getContext().getAuthentication();
  if(authentication==null||!(authentication.getPrincipal() instanceof CustomerSessionContext session)||body.actionToken()==null||!body.actionToken().matches("bga1_[A-Za-z0-9_-]{43}"))throw AbstractSubscriptionController.invalid();
  var context=new SubscriptionCommandContext(new SubscriptionActor.Customer(session),session.siteId(),session.customerId(),session.origin(),request.getHeader("Idempotency-Key"),RequestIdFilter.requestId(request));
  var r=use.issue(new CreateSubscriptionClientSessionUseCase.Command(context,id,operationId,body.actionToken()));
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response(r.provider(),r.flow(),r.publishableKey(),r.clientSecret(),r.actionToken(),r.expiresAt()));
 }
}
