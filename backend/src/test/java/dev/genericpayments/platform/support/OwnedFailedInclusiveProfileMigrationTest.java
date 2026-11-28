package dev.genericpayments.platform.support;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Resource-only contract: no migration, database, Spring context or provider execution. */
class OwnedFailedInclusiveProfileMigrationTest {
    private static String resource(String name)throws Exception{
        try(var input=OwnedFailedInclusiveProfileMigrationTest.class.getResourceAsStream("/db/migration/"+name)){
            assertThat(input).as("Shipped forward migration %s",name).isNotNull();
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private static String function(String source,String name){
        int start=source.indexOf("create function "+name+"(");
        if(start<0)start=source.indexOf("create or replace function "+name+"(");
        assertThat(start).as(name).isGreaterThanOrEqualTo(0);
        int end=source.indexOf("$$;",start);assertThat(end).isGreaterThan(start);
        return source.substring(start,end+3).replace("create or replace function ","create function ");
    }
    @Test void onlyTwoExplicitReplacementsPreserveEveryV34PredicateExceptTheClosedProfileAlternative()throws Exception{
        var original=resource("V34__stripe_billing_owned_failed_renewal.sql");
        var forward=resource("V42__stripe_billing_owned_failed_inclusive_profile.sql");
        String admission="""
            and i.amount_minor>0 and i.trial_days=0 and (i.tax_mode='NONE' or (
                  i.tax_mode='INCLUSIVE' and i.amount_minor=2000 and i.currency='EUR' and i.billing_interval='MONTH'
                  and exists(select 1 from site_subscription_policies fixed_policy where fixed_policy.site_id=i.site_id
                    and fixed_policy.version=i.policy_version and fixed_policy.enabled and fixed_policy.grace_days=3)
                  and exists(select 1 from prices fixed_price where fixed_price.id=i.price_id and fixed_price.site_id=i.site_id
                    and fixed_price.product_id=i.product_id and fixed_price.price_version=i.price_version and fixed_price.tax_code is null))))
            """.stripTrailing();
        String authority="""
            and i.amount_minor>0 and i.trial_days=0 and (i.tax_mode='NONE' or (
               i.tax_mode='INCLUSIVE' and i.amount_minor=2000 and i.currency='EUR' and i.billing_interval='MONTH'
               and policy.grace_days=3 and price.tax_code is null))
            """.stripTrailing();
        String old="and i.amount_minor>0 and i.trial_days=0 and i.tax_mode='NONE'";
        assertThat(function(forward,"require_failed_renewal_admission")).isEqualTo(function(original,"require_failed_renewal_admission").replace(old+")",admission));
        assertThat(function(forward,"owned_failed_renewal_authority")).isEqualTo(function(original,"owned_failed_renewal_authority").replace(old,authority));
        var withoutDefinitions=forward;
        for(var name:java.util.List.of("require_failed_renewal_admission","owned_failed_renewal_authority"))
            withoutDefinitions=withoutDefinitions.replace(function(forward,name).replace("create function ","create or replace function "),"");
        assertThat(withoutDefinitions.replaceAll("(?m)--[^\\r\\n]*","")).isBlank();
    }
    @Test void currentSchemaIncludesTheForwardMigration(){assertThat(CurrentSchemaVersions.EXPECTED).containsSubsequence("41","42");}
}
