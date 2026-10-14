package dev.genericpayments.platform.subscriptions.adapter.in.web;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/customer/subscriptions")
public class CustomerSubscriptionController extends AbstractSubscriptionController {
 public CustomerSubscriptionController(SubscriptionHttpUseCases use){super(use);}
 
}
