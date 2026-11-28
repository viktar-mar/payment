package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionClientSessionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionClientSessionPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import org.springframework.stereotype.Service;
@Service
public class SubscriptionClientSessionService implements CreateSubscriptionClientSessionUseCase {
 private final SubscriptionInteractionTransaction phases;private final org.springframework.beans.factory.ObjectProvider<SubscriptionClientSessionPort> clients;
 public SubscriptionClientSessionService(SubscriptionInteractionTransaction phases,org.springframework.beans.factory.ObjectProvider<SubscriptionClientSessionPort> clients){this.phases=phases;this.clients=clients;}
 public SubscriptionClientSession issue(Command c){
  if(c==null||c.context()==null||!(c.context().actor() instanceof SubscriptionActor.Customer))throw SubscriptionRuleErrors.conflict();
  var state=phases.action(c.context(),c.subscriptionId(),c.operationId());if(state.account().provider()!=PaymentProviderType.STRIPE)throw SubscriptionRuleErrors.conflict();
  var grant=phases.clientGrant(c.context(),state,c.actionToken());var port=clients.getIfAvailable();if(port==null)throw SubscriptionRuleErrors.conflict();
  var result=port.retrieve(new Lookup(state.account(),state.subscription().id(),state.subscription().providerReference(),state.operation().providerOperationKey()),grant,c.actionToken());
  return phases.clientIssued(c.context(),state,grant,c.actionToken(),result);
 }
}
