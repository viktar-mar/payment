package dev.genericpayments.platform.sites.application.service;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.in.CreateSiteCommand;
import dev.genericpayments.platform.sites.application.port.in.CreateSiteUseCase;
import dev.genericpayments.platform.sites.application.port.in.ListSitesUseCase;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.application.port.out.SaveSitePort;
import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SiteService implements CreateSiteUseCase, ListSitesUseCase {
    private final LoadSitePort loadSites;
    private final SaveSitePort saveSites;
    private final LoadPaymentAccountPort loadPaymentAccounts;

    public SiteService(LoadSitePort loadSites, SaveSitePort saveSites,
                       LoadPaymentAccountPort loadPaymentAccounts) {
        this.loadSites = Objects.requireNonNull(loadSites);
        this.saveSites = Objects.requireNonNull(saveSites);
        this.loadPaymentAccounts = Objects.requireNonNull(loadPaymentAccounts);
    }

    public <T extends LoadSitePort & SaveSitePort> SiteService(T sites,
                                                                 LoadPaymentAccountPort loadPaymentAccounts) {
        this(sites, sites, loadPaymentAccounts);
    }

    @Override
    public Site create(CreateSiteCommand command) {
        Objects.requireNonNull(command, "command");
        SiteKey key = SiteKey.of(command.key());
        if (loadSites.existsByKey(key)) {
            throw new DomainValidationException("site key already exists: " + key.value());
        }
        var account = loadPaymentAccounts.findById(command.paymentAccountId())
            .orElseThrow(() -> new DomainValidationException("payment account does not exist"));
        if (account.status() != PaymentAccountStatus.ACTIVE) {
            throw new DomainValidationException("payment account is not active");
        }
        Site site = Site.create(new SiteId(UUID.randomUUID()), key, command.displayName(),
            command.paymentAccountId(), command.allowedOrigins(), command.authenticationModes(),
            command.paymentMethodPolicy());
        return saveSites.save(site);
    }

    @Override
    public List<Site> list() {
        return List.copyOf(loadSites.findAll());
    }
}
