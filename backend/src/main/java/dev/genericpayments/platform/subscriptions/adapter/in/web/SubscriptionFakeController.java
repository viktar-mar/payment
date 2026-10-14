package dev.genericpayments.platform.subscriptions.adapter.in.web;
import dev.genericpayments.platform.subscriptions.application.port.in.ControlFakeSubscriptionUseCase;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;import jakarta.servlet.http.HttpServletRequest;
@RestController @Profile("local") @RequestMapping("/api/v1/admin/subscriptions")
public class SubscriptionFakeController  {
 private final ControlFakeSubscriptionUseCase controls;private final AbstractSubscriptionController boundary;
 public SubscriptionFakeController(SubscriptionHttpUseCases use,ControlFakeSubscriptionUseCase controls){this.boundary=new AbstractSubscriptionController(use){};this.controls=controls;}
 @PostMapping("/{id}/fake-controls") public SubscriptionTransport.SubscriptionFakeResponse control(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionTransport.SubscriptionFakeRequest b){
  var x=controls.control(new ControlFakeSubscriptionUseCase.Command(boundary.context(r,id),id,b.action(),b.scenario(),b.until()));
  return new SubscriptionTransport.SubscriptionFakeResponse(x.subscriptionId(),x.paymentAccountId(),x.action(),x.scenario(),x.until(),x.simulated());
 }
}
