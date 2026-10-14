package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.payments.application.port.in.ListCustomerDonationsUseCase;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.port.out.CustomerPaymentHistoryCursorPort.Position;
import dev.genericpayments.platform.payments.application.model.CustomerDonationHistory;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class CustomerDonationHistoryService implements ListCustomerDonationsUseCase {
    private static final String SHAPE="customer-donations-v1";
    private final DonationPaymentReadPort payments;
    private final CustomerPaymentHistoryCursorPort cursors;
    private final DonationMessageStatePort states;
    public CustomerDonationHistoryService(DonationPaymentReadPort payments,CustomerPaymentHistoryCursorPort cursors,DonationMessageStatePort states){this.payments=payments;this.cursors=cursors;this.states=states;}
    public CustomerDonationHistory list(Query query){
        if(query==null||query.siteId()==null||query.customerId()==null||query.limit()<1||query.limit()>100)throw new DomainValidationException("Invalid Customer history query");
        Position boundary=null;
        if(query.cursor()!=null)try{
            boundary=cursors.decode(query.cursor());
            if(!query.siteId().equals(boundary.siteId())||!query.customerId().equals(boundary.customerId())||!SHAPE.equals(boundary.queryShape())||query.limit()!=boundary.pageSize())throw CustomerPaymentHistoryService.invalidCursor();
        }catch(RuntimeException invalid){throw CustomerPaymentHistoryService.invalidCursor();}
        var found=payments.history(query.siteId(),query.customerId(),boundary==null?null:boundary.createdAt(),boundary==null?null:boundary.paymentId().value(),query.limit()+1);
        boolean more=found.size()>query.limit();var visible=more?found.subList(0,query.limit()):found;
        var state=states.states(query.siteId(),visible.stream().map(p->p.donationId()).collect(Collectors.toSet()));
        String next=null;if(more){var last=visible.getLast();next=cursors.encode(new Position(query.siteId(),query.customerId(),SHAPE,query.limit(),last.createdAt(),new PaymentId(last.paymentId())));}
        return new CustomerDonationHistory(visible.stream().map(p->new CustomerDonationHistory.Item(p.donationId(),p.paymentId(),p.campaignId(),p.campaignKey(),p.campaignVersion(),p.amountMinor(),p.currency(),p.status(),Objects.requireNonNull(state.get(p.donationId())),p.createdAt(),p.updatedAt())).toList(),next);
    }
}
