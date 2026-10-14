package dev.genericpayments.platform.catalog.application.port.in;

import dev.genericpayments.platform.sites.domain.SiteId;

public record CreateProductCommand(SiteId siteId, String key, String name, String description) {}
