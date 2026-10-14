package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.payments.application.port.out.PaymentProviderRegistry;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
/** Canonical origin/metadata policy shared by dynamic creation and handoff issue. */
public final class PartnerPaymentRules {
    private PartnerPaymentRules() {}
    public static URI origin(Site site,URI raw) {
        URI normalized=normalizeOrigin(raw,true);
        if (site.allowedOrigins().stream().map(allowed -> normalizeOrigin(allowed,true)).noneMatch(normalized::equals)) throw CheckoutAuthorizationService.originDenied();
        return normalized;
    }
    public static URI normalizeOrigin(URI raw,boolean strict) {
        if (raw == null || raw.getScheme()==null || raw.getHost()==null || raw.getRawUserInfo()!=null || raw.getRawFragment()!=null
            || strict && (raw.getRawQuery()!=null || raw.getRawPath()!=null && !raw.getRawPath().isEmpty())) throw CheckoutAuthorizationService.originDenied();
        var scheme=raw.getScheme().toLowerCase(Locale.ROOT); var host=raw.getHost().toLowerCase(Locale.ROOT); int port=raw.getPort();
        if (!Set.of("http","https").contains(scheme) || port>65535) throw CheckoutAuthorizationService.originDenied();
        if (port == (scheme.equals("https") ? 443 : 80)) port=-1;
        try { return new URI(scheme,null,host,port,null,null,null); }
        catch (URISyntaxException invalid) { throw CheckoutAuthorizationService.originDenied(); }
    }
    public static void returnUrl(String value,URI origin) {
        if (value==null) return;
        if (value.length()>2048 || value.isBlank()) throw invalid();
        try {
            var url=URI.create(value);
            if (!url.isAbsolute() || url.getHost()==null || url.getRawUserInfo()!=null || url.getRawFragment()!=null || !normalizeOrigin(url,false).equals(origin)) throw invalid();
            utf8(value);
        } catch (IllegalArgumentException | PaymentApplicationException invalidUrl) { throw invalid(); }
    }
    /** Sorted flat JSON, with strict UTF-8 sizing including JSON delimiters/escaping. */
    public static String metadata(Map<String,String> input) {
        if (input==null) input=Map.of();
        if (input.size()>20) throw invalid();
        var sorted=new TreeMap<String,String>();
        input.forEach((key,value) -> {
            if (key==null || key.isBlank() || key.length()>64 || value==null || value.length()>500) throw invalid();
            utf8(key); utf8(value);
            if (key.codePoints().anyMatch(Character::isISOControl) || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
            try { CanonicalFingerprint.requireMetadataPropertyName(key); }
            catch (IllegalArgumentException reservedName) { throw invalid(); }
            sorted.put(key,value);
        });
        var json=new StringBuilder("{"); boolean first=true;
        for (var entry:sorted.entrySet()) {
            if (!first) json.append(','); first=false;
            json.append('"').append(escape(entry.getKey())).append("\":\"").append(escape(entry.getValue())).append('"');
        }
        json.append('}');
        if (utf8(json.toString())>4096) throw invalid();
        return json.toString();
    }
    private static String escape(String text) { return text.replace("\\","\\\\").replace("\"","\\\""); }
    private static int utf8(String value) {
        try { return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining(); }
        catch (CharacterCodingException invalidUtf8) { throw invalid(); }
    }
    public static PaymentAccount account(LoadPaymentAccountPort accounts,PaymentProviderRegistry providers,PaymentAccountId id) {
        var account=accounts.findById(id).filter(a -> a.status()==PaymentAccountStatus.ACTIVE).orElseThrow(CatalogCheckoutTransaction::capability);
        try { providers.select(account).capabilities(account).require(TaxMode.NONE); }
        catch (IllegalStateException unsupported) { throw CatalogCheckoutTransaction.capability(); }
        return account;
    }
    public static void preflight(Stage2TelemetryPort telemetry,Site site,PaymentAccount account) {
        try { telemetry.requireContext(new Stage2TelemetryPort.Context(site.key().value(),Stage2TelemetryPort.Provider.valueOf(account.provider().name()))); }
        catch (IllegalArgumentException invalid) { throw new PaymentApplicationException("SITE_NOT_AVAILABLE",503,"Site not available"); }
    }
    public static PaymentApplicationException notFound() { return new PaymentApplicationException("PAYMENT_NOT_FOUND",404,"Payment not found"); }
    public static PaymentApplicationException state() { return new PaymentApplicationException("PAYMENT_STATE_CONFLICT",409,"Payment state conflict"); }
    static DomainValidationException invalid() { return new DomainValidationException("Invalid dynamic payment request"); }
}
