package dev.genericpayments.platform.donations.application.service;

import dev.genericpayments.platform.donations.application.port.in.*;
import dev.genericpayments.platform.donations.application.port.out.*;
import dev.genericpayments.platform.donations.application.model.DonationViews.*;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.payments.application.model.DonationPaymentViews.Query;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class DonationQueryService implements GetPartnerDonationUseCase,QueryAdminDonationsUseCase {
    private final DonationPaymentQueryPort payments;
    private final QueryDonationStatesUseCase states;
    private final DonationStore donations;
    private final EncryptedSecretPort encryption;
    public DonationQueryService(DonationPaymentQueryPort payments,QueryDonationStatesUseCase states,DonationStore donations,EncryptedSecretPort encryption){this.payments=payments;this.states=states;this.donations=donations;this.encryption=encryption;}
    public Partner get(SiteId site,UUID id){return Partner.from(payments.find(Objects.requireNonNull(site).value(),id).orElseThrow(DonationQueryService::missing));}
    public Page list(Query query){
        var page=payments.list(query);Map<UUID,DonorMessageState> state=new HashMap<>();
        // One bounded batch per Site in an at-most-50-row Admin page; no per-Donation encrypted loads.
        var groups=page.items().stream().collect(Collectors.groupingBy(p->p.siteId(),Collectors.mapping(p->p.donationId(),Collectors.toSet())));
        groups.forEach((site,ids)->state.putAll(states.states(new SiteId(site),ids)));
        return new Page(page.items().stream().map(p->Summary.from(p,Objects.requireNonNull(state.get(p.donationId())))).toList(),page.total(),page.page(),page.size());
    }
    public Detail detail(UUID id){
        var payment=payments.find(null,id).orElseThrow(DonationQueryService::missing);var site=new SiteId(payment.siteId());
        var donation=donations.findById(site,new DonationId(id)).orElseThrow(DonationQueryService::missing);
        String message=null;
        if(donation.messageState()==DonorMessageState.PRESENT)try {
            var framed=encryption.decrypt(donations.findEncryptedMessage(site,donation.id()).orElseThrow());
            if(framed.isEmpty()||framed.charAt(0)!='\u0001')throw new IllegalArgumentException();
            message=framed.substring(1);
            if(!DonorMessage.of(message).value().equals(message))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new PaymentApplicationException("DONATION_MESSAGE_UNAVAILABLE",503,"Donation message unavailable");}
        return new Detail(Summary.from(payment,donation.messageState()),message);
    }
    public static PaymentApplicationException missing(){return new PaymentApplicationException("DONATION_NOT_FOUND",404,"Donation not found");}
}
