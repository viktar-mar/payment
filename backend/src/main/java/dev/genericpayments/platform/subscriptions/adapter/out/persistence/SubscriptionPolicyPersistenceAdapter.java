package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.catalog.application.port.out.LoadProductPort;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.PriceStatus;
import dev.genericpayments.platform.catalog.domain.PriceType;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.catalog.domain.ProductStatus;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPolicyPort;
import dev.genericpayments.platform.subscriptions.domain.PlanTransition;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SubscriptionPolicyPersistenceAdapter implements SubscriptionPolicyPort {
    private static final String TRANSITIONS = """
        select t.site_id,t.product_id,t.from_price_id,t.to_price_id,t.direction,t.immediate_allowed,
               p.version as policy_version
        from subscription_plan_transitions t join site_subscription_policies p on p.site_id=t.site_id
        where t.site_id=? and t.product_id=?
        """;
    private final JdbcTemplate jdbc;
    private final LoadPricePort prices;
    private final LoadProductPort products;
    private final Environment environment;

    public SubscriptionPolicyPersistenceAdapter(JdbcTemplate jdbc, LoadPricePort prices,
                                                 LoadProductPort products, Environment environment) {
        this.jdbc = jdbc;
        this.prices = prices;
        this.products = products;
        this.environment = environment;
    }

    @Override
    public SubscriptionPolicy load(SiteId siteId) {
        return jdbc.query("select * from site_subscription_policies where site_id=?", (rs, row) ->
            new SubscriptionPolicy(siteId, rs.getLong("version"), rs.getBoolean("enabled"), rs.getInt("grace_days"),
                rs.getBoolean("allow_immediate_cancellation"), rs.getBoolean("portal_enabled"),
                rs.getBoolean("defer_upgrades"), Set.of((String[]) rs.getArray("portal_return_urls").getArray())),
            siteId.value()).stream().findFirst().orElseGet(() -> SubscriptionPolicy.defaults(siteId));
    }

    @Override
    @Transactional
    public SubscriptionPolicy save(SubscriptionPolicy policy, long expectedVersion) {
        if (expectedVersion < 0 || policy.version() != expectedVersion || expectedVersion == Long.MAX_VALUE) {
            throw new OptimisticLockingFailureException("Subscription policy version changed");
        }
        policy.portalReturnUrls().forEach(this::validateReturnUrl);
        int updated = jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                update site_subscription_policies set enabled=?,grace_days=?,allow_immediate_cancellation=?,
                  portal_enabled=?,defer_upgrades=?,portal_return_urls=?,version=version+1
                where site_id=? and version=?
                """);
            bindPolicy(statement, policy);
            statement.setObject(7, policy.siteId().value());
            statement.setLong(8, expectedVersion);
            return statement;
        });
        if (updated == 0 && expectedVersion == 0) {
            updated = jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                    insert into site_subscription_policies(enabled,grace_days,allow_immediate_cancellation,
                      portal_enabled,defer_upgrades,portal_return_urls,site_id,version)
                    values (?,?,?,?,?,?,?,1) on conflict (site_id) do nothing
                    """);
                bindPolicy(statement, policy);
                statement.setObject(7, policy.siteId().value());
                return statement;
            });
        }
        if (updated != 1) throw new OptimisticLockingFailureException("Subscription policy version changed");
        return new SubscriptionPolicy(policy.siteId(), expectedVersion + 1, policy.enabled(), policy.graceDays(),
            policy.allowImmediateCancellation(), policy.portalEnabled(), policy.deferUpgrades(), policy.portalReturnUrls());
    }

    @Override
    public Optional<PlanTransition> transition(SiteId siteId, ProductId productId, PriceId fromPriceId, PriceId toPriceId) {
        return jdbc.query(TRANSITIONS + " and t.from_price_id=? and t.to_price_id=?", this::transitionRow,
            siteId.value(), productId.value(), fromPriceId.value(), toPriceId.value()).stream().findFirst();
    }

    @Override
    public List<PlanTransition> transitions(SiteId siteId, ProductId productId) {
        return jdbc.query(TRANSITIONS + " order by t.from_price_id,t.to_price_id", this::transitionRow,
            siteId.value(), productId.value());
    }

    @Override
    @Transactional
    public void saveTransition(PlanTransition transition) {
        validateTransition(transition);
        var current = load(transition.siteId());
        if (current.version() != transition.policyVersion()) {
            throw new OptimisticLockingFailureException("Subscription policy version changed");
        }
        // One shared version invalidates quotes after either policy or transition edits.
        var updated = save(current, transition.policyVersion());
        jdbc.update("""
            insert into subscription_plan_transitions
              (site_id,product_id,from_price_id,to_price_id,direction,immediate_allowed,policy_version)
            values (?,?,?,?,?,?,?) on conflict (site_id,from_price_id,to_price_id)
            do update set direction=excluded.direction,immediate_allowed=excluded.immediate_allowed,
              policy_version=excluded.policy_version
            """, transition.siteId().value(), transition.productId().value(), transition.fromPriceId().value(),
            transition.toPriceId().value(), transition.direction().name(), transition.immediateAllowed(), updated.version());
    }

    private void bindPolicy(PreparedStatement statement, SubscriptionPolicy policy) throws SQLException {
        statement.setBoolean(1, policy.enabled());
        statement.setInt(2, policy.graceDays());
        statement.setBoolean(3, policy.allowImmediateCancellation());
        statement.setBoolean(4, policy.portalEnabled());
        statement.setBoolean(5, policy.deferUpgrades());
        statement.setArray(6, statement.getConnection().createArrayOf("text", policy.portalReturnUrls().toArray(String[]::new)));
    }

    private PlanTransition transitionRow(ResultSet rs, int row) throws SQLException {
        return new PlanTransition(new SiteId(rs.getObject("site_id", UUID.class)),
            new ProductId(rs.getObject("product_id", UUID.class)), new PriceId(rs.getObject("from_price_id", UUID.class)),
            new PriceId(rs.getObject("to_price_id", UUID.class)), PlanTransition.Direction.valueOf(rs.getString("direction")),
            rs.getBoolean("immediate_allowed"), rs.getLong("policy_version"));
    }

    private void validateTransition(PlanTransition transition) {
        var product = products.findById(transition.productId())
            .orElseThrow(() -> new DomainValidationException("Plan transition product does not exist"));
        if (!product.siteId().equals(transition.siteId()) || product.status() != ProductStatus.ACTIVE) {
            throw new DomainValidationException("Plan transition product is unavailable");
        }
        var from = transitionPrice(transition, transition.fromPriceId());
        var to = transitionPrice(transition, transition.toPriceId());
        if (from.publishedAt() == null || to.status() != PriceStatus.PUBLISHED
                || !from.money().currency().equals(to.money().currency())) {
            throw new DomainValidationException("Plan transition prices are unavailable or use different currencies");
        }
    }

    private Price transitionPrice(PlanTransition transition, PriceId id) {
        var price = prices.findById(id).orElseThrow(() -> new DomainValidationException("Plan transition price does not exist"));
        if (!price.siteId().equals(transition.siteId()) || !price.productId().equals(transition.productId())
                || price.type() != PriceType.SUBSCRIPTION) {
            throw new DomainValidationException("Plan transition price does not belong to the subscription product");
        }
        return price;
    }

    private void validateReturnUrl(String value) {
        try {
            if (value.isBlank() || value.length() > 2048 || hasControls(value)) throw invalidReturnUrl();
            var uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || hasControls(uri.getPath()) || hasControls(uri.getQuery())) {
                throw invalidReturnUrl();
            }
            if (uri.getQuery()!=null) for (String parameter:uri.getQuery().split("&")) {
                String name=parameter.split("=",2)[0];
                if (name.equals("subscriptionPortal")||name.equals("operationId")) throw invalidReturnUrl();
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) return;
            if ("http".equalsIgnoreCase(uri.getScheme()) && environment.matchesProfiles("local")
                    && Set.of("localhost", "127.0.0.1", "[::1]", "::1").stream().anyMatch(uri.getHost()::equalsIgnoreCase)) return;
            throw invalidReturnUrl();
        } catch (URISyntaxException failure) {
            throw invalidReturnUrl();
        }
    }

    private static boolean hasControls(String value) {
        return value != null && value.chars().anyMatch(Character::isISOControl);
    }

    private static DomainValidationException invalidReturnUrl() {
        return new DomainValidationException("Invalid subscription portal return URL");
    }
}
