package dev.genericpayments.platform.donations.adapter.out.persistence;
import dev.genericpayments.platform.donations.application.port.out.DonationStateReadPort;
import dev.genericpayments.platform.donations.domain.DonorMessageState;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class DonationStateReadAdapter implements DonationStateReadPort {
    private final NamedParameterJdbcTemplate jdbc;
    public DonationStateReadAdapter(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public Map<UUID,DonorMessageState> states(SiteId site,Set<UUID> ids){
        Map<UUID,DonorMessageState> result=new HashMap<>();
        jdbc.query("select id,message_state from donations where site_id=:site and id in (:ids)",Map.of("site",site.value(),"ids",ids),
            (org.springframework.jdbc.core.RowCallbackHandler)r->result.put(r.getObject("id",UUID.class),DonorMessageState.valueOf(r.getString("message_state"))));
        return Map.copyOf(result);
    }
}
