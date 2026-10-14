package dev.genericpayments.platform.donations.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.donations.application.port.in.QueryDonationStatesUseCase;
import dev.genericpayments.platform.donations.application.port.out.DonationPaymentQueryPort;
import dev.genericpayments.platform.donations.application.port.out.DonationStore;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.payments.application.model.DonationPaymentViews;
import dev.genericpayments.platform.payments.application.port.in.ListCustomerDonationsUseCase;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.service.CustomerDonationHistoryService;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DonationQueryProjectionTest {
    @Test void customerEnrichmentIsOneBatchOfOnlyTheVisibleHundredIds() {
        var site = new SiteId(UUID.randomUUID()); var customer = UUID.randomUUID();
        var payments = mock(DonationPaymentReadPort.class);
        var cursors = mock(CustomerPaymentHistoryCursorPort.class);
        var states = mock(DonationMessageStatePort.class);
        var rows = IntStream.range(0, 101).mapToObj(i -> row(site)).toList();
        when(payments.history(site, customer, null, null, 101)).thenReturn(rows);
        var visibleIds = rows.subList(0, 100).stream().map(DonationPaymentViews.Item::donationId).collect(Collectors.toSet());
        when(states.states(site, visibleIds)).thenReturn(visibleIds.stream().collect(Collectors.toMap(id -> id, id -> "ABSENT")));
        var result = new CustomerDonationHistoryService(payments, cursors, states)
            .list(new ListCustomerDonationsUseCase.Query(site, customer, 100, null));
        assertThat(result.items()).hasSize(100);
        verify(states).states(site, visibleIds); verifyNoMoreInteractions(states);
        verify(payments).history(site, customer, null, null, 101); verifyNoMoreInteractions(payments);
    }

    @Test void adminPageEnrichesOneBoundedBatchPerSiteAndPartnerNeverLoadsDonationOrCiphertext() {
        var first = new SiteId(UUID.randomUUID()); var second = new SiteId(UUID.randomUUID());
        var payments = mock(DonationPaymentQueryPort.class); var states = mock(QueryDonationStatesUseCase.class);
        var store = mock(DonationStore.class); var encryption = mock(EncryptedSecretPort.class);
        var rows = IntStream.range(0, 50).mapToObj(i -> row(i < 25 ? first : second)).toList();
        var query = new DonationPaymentViews.Query(null, null, null, null, null, null, null, null, 0, 50);
        when(payments.list(query)).thenReturn(new DonationPaymentViews.Page(rows, 1000, 0, 50));
        for (var site : List.of(first, second)) {
            var ids = rows.stream().filter(r -> r.siteId().equals(site.value())).map(DonationPaymentViews.Item::donationId).collect(Collectors.toSet());
            when(states.states(site, ids)).thenReturn(ids.stream().collect(Collectors.toMap(id -> id, id -> DonorMessageState.PRESENT)));
        }
        var service = new DonationQueryService(payments, states, store, encryption);
        assertThat(service.list(query).items()).hasSize(50).allMatch(i -> i.hasMessage());
        for (var site : List.of(first, second)) verify(states).states(eq(site), argThat(ids -> ids.size() == 25));
        verifyNoMoreInteractions(states); verifyNoInteractions(store, encryption);
        var item = rows.getFirst(); when(payments.find(first.value(), item.donationId())).thenReturn(Optional.of(item));
        assertThat(service.get(first, item.donationId()).paymentId()).isEqualTo(item.paymentId());
        verifyNoInteractions(store, encryption);
    }

    @ParameterizedTest @EnumSource(value = DonorMessageState.class, names = {"ABSENT", "REDACTED"})
    void nonPresentDetailNeverLoadsOrDecryptsCiphertext(DonorMessageState state) {
        var site = new SiteId(UUID.randomUUID()); var row = row(site);
        var payments = mock(DonationPaymentQueryPort.class); var states = mock(QueryDonationStatesUseCase.class);
        var store = mock(DonationStore.class); var encryption = mock(EncryptedSecretPort.class);
        var id = new DonationId(row.donationId());
        var donation = Donation.create(id, site, new DonationCampaignId(row.campaignId()),
            state == DonorMessageState.REDACTED ? DonationStore.messageReference(id) : null, row.createdAt());
        if (state == DonorMessageState.REDACTED) donation = donation.redactMessage(row.createdAt());
        when(payments.find(null, id.value())).thenReturn(Optional.of(row));
        when(store.findById(site, id)).thenReturn(Optional.of(donation));
        var result = new DonationQueryService(payments, states, store, encryption).detail(id.value());
        assertThat(result.message()).isNull(); assertThat(result.donation().messageState()).isEqualTo(state);
        verify(store).findById(site, id); verifyNoMoreInteractions(store); verifyNoInteractions(encryption, states);
    }

    private static DonationPaymentViews.Item row(SiteId site) {
        return new DonationPaymentViews.Item(UUID.randomUUID(), UUID.randomUUID(), site.value(), null, UUID.randomUUID(),
            "safe-key", 1, 2000, "EUR", PaymentStatus.CREATED, Instant.parse("2026-09-13T10:15:30Z"), Instant.parse("2026-09-13T10:15:30Z"));
    }
}
