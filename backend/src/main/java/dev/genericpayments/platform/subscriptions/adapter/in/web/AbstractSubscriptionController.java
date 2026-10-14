package dev.genericpayments.platform.subscriptions.adapter.in.web;
import static dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionTransport.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionQueryService;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
/** Shared endpoint implementation. Role prefixes select identity, never request-body ownership. */
public abstract class AbstractSubscriptionController {
 protected final SubscriptionHttpUseCases use;
 protected AbstractSubscriptionController(SubscriptionHttpUseCases use){this.use=use;}
 protected SubscriptionActor actor(){var p=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  if(p instanceof CustomerSessionContext c)return new SubscriptionActor.Customer(c);
  if(p instanceof dev.genericpayments.platform.partners.adapter.in.web.AuthenticatedSite ptn)return new SubscriptionActor.Partner(new dev.genericpayments.platform.partners.application.port.in.AuthenticatedSite(ptn.siteId(),ptn.keyId(),ptn.scopes()));
  if(p instanceof AuthenticatedAdmin a)return new SubscriptionActor.Admin(a);throw invalid();
 }
 protected SiteId site(HttpServletRequest r,UUID body){var a=actor();if(a instanceof SubscriptionActor.Customer c)return c.session().siteId();if(a instanceof SubscriptionActor.Partner p)return p.site().siteId();return new SiteId(body==null?requiredUuid(r.getParameter("site_id")):body);}
 protected CustomerId customer(HttpServletRequest r,UUID body,boolean required){var a=actor();if(a instanceof SubscriptionActor.Customer c)return c.session().customerId();String value=r.getParameter("customer_id");UUID id=body==null?(value==null?null:requiredUuid(value)):body;if(id==null&&required)throw invalid();return id==null?null:new CustomerId(id);}
 protected SubscriptionCommandContext context(HttpServletRequest r,UUID id){var a=actor();var site=site(r,null);var sub=use.query().get(a,site,id).subscription();return context(r,a,site,sub.customerId());}
 protected SubscriptionCommandContext context(HttpServletRequest r,SubscriptionActor a,SiteId site,CustomerId customer){return new SubscriptionCommandContext(a,site,customer,a instanceof SubscriptionActor.Customer c?c.session().origin():null,r.getHeader("Idempotency-Key"),RequestIdFilter.requestId(r));}
 static UUID requiredUuid(String s){try{if(s==null||!s.matches("[0-9a-fA-F-]{36}"))throw invalid();return UUID.fromString(s);}catch(IllegalArgumentException e){throw invalid();}}
 static PaymentApplicationException invalid(){return new PaymentApplicationException("INVALID_REQUEST",400,"Invalid subscription request");}
 @GetMapping public SubscriptionPage list(HttpServletRequest r,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,@RequestParam(name="product_id",required=false) UUID product,@RequestParam(required=false) String state){
  var p=use.query().list(new QuerySubscriptionsUseCase.Query(actor(),site(r,null),customer(r,null,actor() instanceof SubscriptionActor.Partner),product==null?null:new ProductId(product),state,limit,cursor));return new SubscriptionPage(p.items().stream().map(SubscriptionTransport::view).toList(),p.nextCursor());
 }
 @PostMapping public ResponseEntity<SubscriptionMutationResponse> create(HttpServletRequest r,@RequestBody SubscriptionCreateRequest b){
  if(b.priceId()==null||b.recurringConsent()==null)throw invalid();var a=actor();return accepted(use.create().create(new CreateSubscriptionUseCase.Command(context(r,a,site(r,b.siteId()),customer(r,b.customerId(),true)),new PriceId(b.priceId()),b.recurringConsent())));
 }
 @GetMapping("/{id}") public SubscriptionResponse get(HttpServletRequest r,@PathVariable UUID id){return view(use.query().get(actor(),site(r,null),id));}
 @GetMapping("/{id}/events") public SubscriptionEventPage events(HttpServletRequest r,@PathVariable UUID id,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor){var p=use.query().history(actor(),site(r,null),id,limit,cursor);return new SubscriptionEventPage(p.items().stream().map(SubscriptionTransport::event).toList(),p.nextCursor());}
 @GetMapping("/{id}/billing-records") public SubscriptionBillingPage bills(HttpServletRequest r,@PathVariable UUID id,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor){var p=use.query().billingRecords(actor(),site(r,null),id,limit,cursor);return new SubscriptionBillingPage(p.items().stream().map(SubscriptionTransport::billing).toList(),p.nextCursor());}
 @PostMapping("/{id}/change-preview") public SubscriptionQuoteResponse preview(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionPreviewRequest b){if(b.targetPriceId()==null||b.timing()==null)throw invalid();return quote(use.preview().preview(new PreviewSubscriptionChangeUseCase.Query(context(r,id),id,new PriceId(b.targetPriceId()),b.timing())));}
 @PostMapping("/{id}/changes") public ResponseEntity<SubscriptionMutationResponse> change(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionChangeRequest b){if(b.quoteId()==null)throw invalid();return accepted(use.change().change(new ChangeSubscriptionPlanUseCase.Command(context(r,id),id,b.quoteId())));}
 @PostMapping("/{id}/changes/clear") public ResponseEntity<SubscriptionMutationResponse> clear(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionEmptyRequest b){return accepted(use.clear().clear(new ClearSubscriptionChangeUseCase.Command(context(r,id),id)));}
 @PostMapping("/{id}/cancellation") public ResponseEntity<SubscriptionMutationResponse> cancel(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionCancelRequest b){if(b.mode()==null)throw invalid();return accepted(use.cancel().cancel(new CancelSubscriptionUseCase.Command(context(r,id),id,b.mode(),b.confirmation())));}
 @PostMapping("/{id}/cancellation/withdraw") public ResponseEntity<SubscriptionMutationResponse> withdraw(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionEmptyRequest b){return accepted(use.withdraw().withdraw(new WithdrawSubscriptionCancellationUseCase.Command(context(r,id),id)));}
 @PostMapping("/{id}/method-setups") public ResponseEntity<SubscriptionMutationResponse> setup(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionConsentRequest b){if(b.recurringConsent()==null)throw invalid();return accepted(use.setup().setup(new SetupSubscriptionMethodUseCase.Command(context(r,id),id,b.recurringConsent())));}
 @PostMapping("/{id}/recoveries") public ResponseEntity<SubscriptionMutationResponse> recover(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionRecoveryRequest b){if(b.billingRecordId()==null)throw invalid();return accepted(use.recover().recover(new RecoverSubscriptionUseCase.Command(context(r,id),id,b.billingRecordId())));}
 @PostMapping("/{id}/resumptions") public ResponseEntity<SubscriptionMutationResponse> resume(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionConsentRequest b){if(b.recurringConsent()==null)throw invalid();return accepted(use.resume().resume(new ResumeSubscriptionUseCase.Command(context(r,id),id,b.recurringConsent())));}
 @GetMapping("/{id}/operations/{operationId}") public SubscriptionOperationResponse operation(HttpServletRequest r,@PathVariable UUID id,@PathVariable UUID operationId){use.query().get(actor(),site(r,null),id);var op=use.query().operation(actor(),site(r,null),operationId);if(!id.equals(op.subscriptionId()))throw SubscriptionQueryService.missing();return safeOperation(op);}
 @GetMapping("/{id}/operations/{operationId}/action") public SubscriptionActionResponse action(HttpServletRequest r,@PathVariable UUID id,@PathVariable UUID operationId){return SubscriptionTransport.action(use.action().get(new GetSubscriptionActionUseCase.Query(context(r,id),id,operationId)));}
 @PostMapping("/{id}/operations/{operationId}/action-confirmations") public ResponseEntity<SubscriptionMutationResponse> confirm(HttpServletRequest r,@PathVariable UUID id,@PathVariable UUID operationId,@RequestBody SubscriptionActionRequest b){if(b.actionToken()==null||b.actionToken().length()>4096)throw invalid();return accepted(use.confirm().confirm(new ConfirmSubscriptionActionUseCase.Command(context(r,id),id,operationId,b.actionToken())));}
 @PostMapping("/{id}/portal-sessions") public ResponseEntity<SubscriptionPortalResponse> portal(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionPortalRequest b){if(b.returnUrl()==null||b.returnUrl().length()>2048)throw invalid();return ResponseEntity.status(201).body(SubscriptionTransport.portal(use.portal().create(new CreateSubscriptionPortalUseCase.Command(context(r,id),id,b.returnUrl()))));}
 protected ResponseEntity<SubscriptionMutationResponse> reconciliation(HttpServletRequest r,UUID id){if(actor() instanceof SubscriptionActor.Customer)throw invalid();return accepted(use.reconcile().reconcile(new ReconcileSubscriptionUseCase.Command(context(r,id),id)));}
 private static ResponseEntity<SubscriptionMutationResponse> accepted(SubscriptionOperationResult r){return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(SubscriptionTransport.operation(r));}
}
