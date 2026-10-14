package dev.genericpayments.platform.acceptance;

import dev.genericpayments.platform.support.AdminApiClient;
import io.cucumber.spring.ScenarioScope;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MvcResult;

@Component @ScenarioScope
public class AcceptanceWorld {
    private final Map<String, String> siteIds = new HashMap<>();
    private final Map<String, String> productIds = new HashMap<>();
    private final Map<String, String> priceIds = new HashMap<>();
    private AdminApiClient api;
    private String accountId;
    private MvcResult lastResponse;
    private final Map<String, String> paymentFlow = new HashMap<>();
    private final Map<String, String> customerFlow = new HashMap<>();
    public Map<String, String> customerFlow() { return customerFlow; }
    public Map<String, String> paymentFlow() { return paymentFlow; }
    // Access through methods so the Spring scenario proxy resolves its current target.
    public Map<String, String> siteIds() { return siteIds; }
    public Map<String, String> productIds() { return productIds; }
    public Map<String, String> priceIds() { return priceIds; }
    public AdminApiClient api() { return api; }
    public void api(AdminApiClient value) { api = value; }
    public String accountId() { return accountId; }
    public void accountId(String value) { accountId = value; }
    public MvcResult lastResponse() { return lastResponse; }
    public void lastResponse(MvcResult value) { lastResponse = value; }
}
