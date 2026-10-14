package dev.genericpayments.platform.partners.domain;
import java.util.*;
public record SiteApiKeyId(UUID value) { public SiteApiKeyId { Objects.requireNonNull(value); } }
