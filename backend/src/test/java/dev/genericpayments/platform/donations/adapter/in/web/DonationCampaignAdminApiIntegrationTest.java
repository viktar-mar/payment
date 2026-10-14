package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import java.util.*;
import org.junit.jupiter.api.Test;

class DonationCampaignAdminApiIntegrationTest extends CampaignApiTestSupport {
    @Test void optionalAndMultilineTextRemainDistinctReplayIntents() throws Exception {
        var withoutPurpose=CONFIG.replace("\"Medical support\"","null");
        var id=JsonPath.<String>read(write("POST",path,withoutPurpose,"optional").andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString(),"$.id");
        write("POST",path,withoutPurpose,"optional").andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        write("POST",path,withoutPurpose.replace("null","\"\""),"optional").andExpect(status().isConflict());
        var multiline=CONFIG.replace("Medical support","First line\\nSecond line").replace("}",",\"expected_version\":0}");
        write("PUT",path+"/"+id,multiline,"multiline").andExpect(status().isOk())
            .andExpect(jsonPath("$.purpose_description").value("First line\nSecond line"));
    }
    @Test void createsDraftAndListsOnlyTheOwningSiteWithStablePagination() throws Exception {
        var id=create();
        api.getJson(path+"/"+id).andExpect(status().isOk()).andExpect(jsonPath("$.site_id").value(site))
            .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.activated_once").value(false)).andExpect(jsonPath("$.suggested_amounts[0]").value(500))
            .andExpect(jsonPath("$.suggested_amounts[1]").value(100)).andExpect(jsonPath("$.suggested_amounts.length()").value(2));
        api.getJson(path+"?limit=1&offset=0").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(id));
        api.getJson(path+"?limit=1&offset=1").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        api.getJson(path.replace(site,otherSite)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        api.getJson(path.replace(site,otherSite)+"/"+id).andExpect(status().isNotFound());
        api.getJson(path+"?limit=51").andExpect(status().isBadRequest());
        api.getJson(path+"?offset=-1").andExpect(status().isBadRequest());
    }
    @Test void paginationUsesAnIdTieBreakerForEqualCreationTimes() throws Exception {
        var ids=new ArrayList<String>();ids.add(create());
        for(var key:List.of("help-two","help-three")) ids.add(JsonPath.read(write("POST",path,CONFIG.replace("help-now",key),key)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id"));
        ids.sort(Comparator.reverseOrder());
        for(int offset=0;offset<ids.size();offset++) api.getJson(path+"?limit=1&offset="+offset).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(ids.get(offset)));
        api.getJson(path+"?limit=1&offset=3").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }
    @Test void duplicateKeyConflictsOnlyWithinTheSiteAndRejectedMutationRollsBackReceipt() throws Exception {
        create(); write("POST",path,CONFIG,"other-intent").andExpect(status().isConflict());
        write("POST",path.replace(site,otherSite),CONFIG,"other-intent").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from donation_campaigns",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records",Integer.class)).isEqualTo(2);
    }
    @Test void updateAndLifecycleIncrementExactlyOnceAndCurrencyRemainsLockedAfterPause() throws Exception {
        var id=create();
        write("PUT",path+"/"+id,CONFIG.replace("EUR","USD").replace("[500,100]","[100,500]").replace("}",",\"expected_version\":0}"),"update")
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.suggested_amounts[0]").value(100)).andExpect(jsonPath("$.suggested_amounts[1]").value(500));
        changeStatus(id,"ACTIVE",1,"activate").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.activated_once").value(true));
        changeStatus(id,"PAUSED",2,"pause").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        write("PUT",path+"/"+id,CONFIG.replace("}",",\"expected_version\":3}"),"change-currency").andExpect(status().isBadRequest());
        changeStatus(id,"ACTIVE",3,"resume").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4));
        changeStatus(id,"ARCHIVED",4,"archive").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(5));
        changeStatus(id,"ACTIVE",5,"revive").andExpect(status().isBadRequest());
        write("PUT",path+"/"+id,CONFIG.replace("EUR","USD").replace("}",",\"expected_version\":5}"),"edit-archived").andExpect(status().isBadRequest());
    }
    @Test void staleVersionsWrongSiteAndMissingExpectedVersionsCannotWrite() throws Exception {
        var id=create(); changeStatus(id,"ACTIVE",0,"activate").andExpect(status().isOk());
        changeStatus(id,"PAUSED",0,"stale").andExpect(status().isConflict());
        write("PUT",path+"/"+id,CONFIG.replace("}",",\"expected_version\":0}"),"stale-update").andExpect(status().isConflict());
        write("PUT",path+"/"+id,CONFIG,"missing-version").andExpect(status().isBadRequest());
        write("POST",path+"/"+id+"/status","{\"status\":\"PAUSED\"}","missing-version").andExpect(status().isBadRequest());
        write("PUT",path.replace(site,otherSite)+"/"+id,CONFIG.replace("}",",\"expected_version\":1}"),"wrong-site").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select version from donation_campaigns where id=?",Long.class,UUID.fromString(id))).isEqualTo(1);
    }
    @Test void replayDoesNotMutateOrAuditAgainAndChangedIntentConflicts() throws Exception {
        var id=create();
        write("POST",path,CONFIG,"create-intent").andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        write("POST",path,CONFIG.replace("Medical support","Changed"),"create-intent").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        changeStatus(id,"ACTIVE",0,"activate").andExpect(status().isOk());
        changeStatus(id,"ACTIVE",0,"activate").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        changeStatus(id,"PAUSED",1,"activate").andExpect(status().isConflict());
        var edited=CONFIG.replace("Medical support","Updated").replace("}",",\"expected_version\":1}");
        write("PUT",path+"/"+id,edited,"update").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        write("PUT",path+"/"+id,edited,"update").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where resource_type='DONATION_CAMPAIGN'",Integer.class)).isEqualTo(3);
        var details=jdbc.queryForList("select details::text from audit_log where resource_type='DONATION_CAMPAIGN'",String.class);
        assertThat(details).allSatisfy(value->assertThat(value).contains("site_id", "version", "status").doesNotContain("Medical", "Updated", "script", "purpose", "display", "message"));
    }
    @Test void authorizationCsrfAndIdempotencyAreRequired() throws Exception {
        api.mvc.perform(get(path)).andExpect(status().isUnauthorized());
        api.mvc.perform(post(path).cookie(api.session).contentType("application/json").content(CONFIG)).andExpect(status().isForbidden());
        write("POST",path,CONFIG,null).andExpect(status().isBadRequest());
        write("POST",path,CONFIG," ").andExpect(status().isBadRequest());
        write("POST",path,CONFIG,"x".repeat(256)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from donation_campaigns",Integer.class)).isZero();
    }
    @Test void serverAmountPolicyAndImmutableKeyAreAuthoritative() throws Exception {
        write("POST",path,CONFIG.replace("[500,100]","[100,100]"),"duplicate").andExpect(status().isBadRequest());
        write("POST",path,CONFIG.replace("[500,100]","[99]"),"range").andExpect(status().isBadRequest());
        write("POST",path,CONFIG.replace("EUR","ZZZ"),"currency").andExpect(status().isBadRequest());
        var id=create();
        write("PUT",path+"/"+id,CONFIG.replace("help-now","new-key").replace("}",",\"expected_version\":0}"),"key").andExpect(status().isBadRequest());
        api.getJson(path.replace(site,UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }
}
