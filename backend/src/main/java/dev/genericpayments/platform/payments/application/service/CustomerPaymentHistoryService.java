package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.payments.application.model.CustomerPaymentHistory;
import dev.genericpayments.platform.payments.application.model.CustomerPaymentHistory.Catalog;
import dev.genericpayments.platform.payments.application.model.CustomerPaymentHistory.Item;
import dev.genericpayments.platform.payments.application.port.in.ListCustomerPaymentsUseCase;
import dev.genericpayments.platform.payments.application.port.out.CustomerPaymentHistoryCursorPort;
import dev.genericpayments.platform.payments.application.port.out.CustomerPaymentHistoryCursorPort.Position;
import dev.genericpayments.platform.payments.application.port.out.LoadPaymentPort;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.List;

public final class CustomerPaymentHistoryService implements ListCustomerPaymentsUseCase {
    public static final String QUERY_SHAPE="customer-payments-v1";
    private final LoadPaymentPort payments;
    private final CustomerPaymentHistoryCursorPort cursors;
    public CustomerPaymentHistoryService(LoadPaymentPort payments,CustomerPaymentHistoryCursorPort cursors) {
        this.payments=payments;this.cursors=cursors;
    }
    @Override public CustomerPaymentHistory list(Query query) {
        if(query==null||query.siteId()==null||query.customerId()==null||query.limit()<1||query.limit()>100)
            throw new DomainValidationException("Invalid Customer payment history query");
        Position boundary=null;
        if(query.cursor()!=null) {
            if(query.cursor().isBlank()||query.cursor().length()>4096) throw invalidCursor();
            try {
                boundary=cursors.decode(query.cursor());
                if(!boundary.siteId().equals(query.siteId())||!boundary.customerId().equals(query.customerId())
                        ||!QUERY_SHAPE.equals(boundary.queryShape())||boundary.pageSize()!=query.limit()) throw invalidCursor();
            } catch (RuntimeException invalid) { throw invalidCursor(); }
        }
        List<Payment> found=payments.findCustomerHistory(query.siteId(),query.customerId(),
            boundary==null?null:boundary.createdAt(),boundary==null?null:boundary.paymentId(),query.limit()+1);
        boolean more=found.size()>query.limit();
        List<Payment> visible=more?found.subList(0,query.limit()):found;
        String next=null;
        if(more) {
            Payment last=visible.getLast();
            next=cursors.encode(new Position(query.siteId(),query.customerId(),QUERY_SHAPE,query.limit(),last.createdAt(),last.id()));
        }
        return new CustomerPaymentHistory(visible.stream().map(CustomerPaymentHistoryService::item).toList(),next);
    }
    private static Item item(Payment payment) {
        Catalog catalog=payment.source()==dev.genericpayments.platform.payments.domain.PaymentSource.SUBSCRIPTION||payment.priceSnapshot()==null?null:new Catalog(payment.priceSnapshot().priceId().value(),
            payment.priceSnapshot().priceKey(),payment.priceSnapshot().version());
        return new Item(payment.id().value(),payment.source(),payment.money().minorUnits(),payment.money().currency(),
            payment.status(),payment.createdAt(),payment.updatedAt(),catalog);
    }
    public static PaymentApplicationException invalidCursor() {
        return new PaymentApplicationException("CUSTOMER_HISTORY_CURSOR_INVALID",400,"Invalid Customer history cursor");
    }
}
