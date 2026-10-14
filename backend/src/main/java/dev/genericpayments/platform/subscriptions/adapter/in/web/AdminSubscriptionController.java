package dev.genericpayments.platform.subscriptions.adapter.in.web;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;import jakarta.servlet.http.HttpServletRequest;import org.springframework.http.ResponseEntity;
@RestController @RequestMapping("/api/v1/admin/subscriptions")
public class AdminSubscriptionController extends AbstractSubscriptionController {
 public AdminSubscriptionController(SubscriptionHttpUseCases use){super(use);}
 @PostMapping("/{id}/reconcile") public ResponseEntity<SubscriptionTransport.SubscriptionMutationResponse> reconcile(HttpServletRequest r,@PathVariable UUID id,@RequestBody SubscriptionTransport.SubscriptionEmptyRequest b){return reconciliation(r,id);}
}
