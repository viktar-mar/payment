package dev.genericpayments.platform.donations.application.service;

import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.donations.application.port.in.CreateDonationCheckoutUseCase;
import dev.genericpayments.platform.donations.application.port.out.*;
import dev.genericpayments.platform.donations.application.model.CreateDonationCheckoutCommand;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

public abstract class DonationFinancialTestFixture extends CheckoutTestFixture {
    protected static final String MESSAGE="Task5 donor café private 7q9";
    protected static final String EMAIL="task5-private-7q9@example.test";
    protected static final String SUBJECT="task5-private-subject-7q9";
    @Autowired protected DonationCampaignStore campaigns;
    @Autowired protected DonationStore donations;
    @Autowired protected CreateDonationCheckoutUseCase donationCheckout;
    @Autowired protected CustomerStore customers;
    @Autowired protected CustomerSessionService sessions;
    @Autowired protected CustomerSessionResolver resolver;
    @Autowired protected EncryptedSecretPort encryption;
    @Autowired protected KeyedDigestPort digests;
    @Autowired protected CustomerIdentityStore identities;
    protected DonationCampaign campaign;
    @BeforeEach void donationCampaign() {
        campaign=campaigns.insert(DonationCampaign.draft(new DonationCampaignId(UUID.randomUUID()),site,new DonationCampaignKey("help-now"),
            "Help",null,"EUR",List.of(2000L),100,10000,true,true,INITIAL_TIME));
        campaign=campaigns.update(campaign.activate(INITIAL_TIME),0);
    }
    protected Customer customer() {
        var id=new CustomerId(UUID.randomUUID());
        var email=digests.digest(KeyedDigestPort.Purpose.EMAIL_LOOKUP,EMAIL);
        var c=customers.save(Customer.create(id,site,CustomerStore.emailReference(id),email.keyVersion()+":"+email.value(),INITIAL_TIME),encryption.encrypt(EMAIL));
        var subject=digests.digest(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,SUBJECT+id.value());
        String ref=subject.keyVersion()+":"+subject.value();
        identities.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,id,CustomerAuthenticationMode.SITE_TOKEN,"safe-provider",ref,INITIAL_TIME),List.of(ref));
        return c;
    }
    protected Payment donation(Customer customer,String message) {
        dev.genericpayments.platform.customers.application.model.CustomerSessionContext context=null;
        if(customer!=null) {
            var token=sessions.issue(site,customer.id(),"https://shop.test");
            context=resolver.resolveOptional("Bearer "+token.rawToken(),site,"https://shop.test",CustomerSessionScope.CHECKOUT_CREATE).orElseThrow();
        }
        var result=donationCheckout.create(new CreateDonationCheckoutCommand("test-site","help-now",URI.create("https://shop.test"),context,2000,message,UUID.randomUUID().toString(),"task5-create"));
        return load.findById(site,new PaymentId(result.checkout().view().paymentId())).orElseThrow();
    }
    protected Payment source(PaymentSource source) {
        return switch(source) {
            case DONATION -> donation(null,MESSAGE);
            case SUBSCRIPTION -> throw new IllegalArgumentException("Subscription cash has its own fixture");
            case DYNAMIC -> payment();
            case CATALOG -> Payment.catalog(new PaymentId(UUID.randomUUID()),site,account.id(),new PriceSnapshot(price.id(),price.key().value(),price.version(),price.money(),price.taxMode()),Map.of(),INITIAL_TIME);
        };
    }
}
