package dev.genericpayments.platform.paymentaccounts.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.paymentaccounts.application.port.in.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.ListPaymentAccountsPort;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1/payment-accounts")
public class PaymentAccountAdminController {
    private final CreatePaymentAccountUseCase create;
    private final ListPaymentAccountsPort accounts;
    private final AdminAuditFacade audit;
    public PaymentAccountAdminController(CreatePaymentAccountUseCase create, ListPaymentAccountsPort accounts, AdminAuditFacade audit) {
        this.create = create; this.accounts = accounts; this.audit = audit;
    }
    @GetMapping @Operation(operationId = "listPaymentAccounts")
    public List<PaymentAccountDto> list() { return accounts.findAll().stream().map(PaymentAccountDto::from).toList(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional @Operation(operationId = "createPaymentAccount")
    public PaymentAccountDto create(@Valid @RequestBody PaymentAccountDto.CreateRequest body,
                                    @AuthenticationPrincipal AuthenticatedAdmin actor, HttpServletRequest request) {
        var account = create.create(new CreatePaymentAccountCommand(body.key(), body.provider(), body.mode(),
            body.externalAccountId(), body.credentialsRef()));
        audit.record(AuditAction.PAYMENT_ACCOUNT_CREATED, "payment_account", account.id().value(), actor, request,
            Map.of("key", account.key(), "provider", account.provider().name()));
        return PaymentAccountDto.from(account);
    }
}
