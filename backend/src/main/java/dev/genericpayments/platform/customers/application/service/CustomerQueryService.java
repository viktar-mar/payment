package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.model.SafeCustomerView;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;

public final class CustomerQueryService implements QueryAdminCustomersUseCase,GetPartnerCustomerUseCase {
    // Same deliberately coarse display value as the existing current-Customer endpoint.
    private static final String MASKED_EMAIL="***@***";
    private final CustomerStore customers;
    private final CustomerIdentityStore identities;
    private final CustomerTransactionPort transactions;
    public CustomerQueryService(CustomerStore customers,CustomerIdentityStore identities,CustomerTransactionPort transactions) {
        this.customers=customers;this.identities=identities;this.transactions=transactions;
    }
    @Override public Page list(Query query) {
        if(query.limit()<1||query.limit()>50||query.offset()<0||query.offset()>100_000
            ||query.maskedEmail()!=null&&query.maskedEmail().length()>254)throw new DomainValidationException("Invalid Customer query");
        if(query.maskedEmail()!=null&&!MASKED_EMAIL.equals(query.maskedEmail()))return new Page(List.of());
        return transactions.required(() -> new Page(customers.query(query.siteId(),query.status(),query.customerId(),
            query.maskedEmail()==null?null:true,query.limit(),query.offset()).stream().map(this::view).toList()));
    }
    @Override public SafeCustomerView get(SiteId site,CustomerId id) {
        return transactions.required(() -> view(customers.findById(site,id).orElseThrow(CustomerQueryService::unavailable)));
    }
    @Override public SafeCustomerView getActive(SiteId site,CustomerId id) {
        return transactions.required(() -> view(customers.findById(site,id).filter(value -> value.status()==CustomerStatus.ACTIVE)
            .orElseThrow(CustomerQueryService::unavailable)));
    }
    private SafeCustomerView view(Customer customer) {
        return new SafeCustomerView(customer.id().value(),customer.siteId().value(),customer.status(),
            customer.encryptedEmailRef().isPresent()?MASKED_EMAIL:null,customer.createdAt(),customer.updatedAt(),customer.anonymizedAt().orElse(null),
            identities.list(customer.siteId(),customer.id()).stream().map(identity -> new SafeCustomerView.Identity(identity.mode(),identity.issuerKey(),
                identity.createdAt(),identity.verifiedAt(),identity.lastUsedAt())).toList());
    }
    static PaymentApplicationException unavailable(){return new PaymentApplicationException("CUSTOMER_NOT_AVAILABLE",404,"Customer not available");}
}
