package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.JsonObject;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.Authority;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture.Transport;

final class OwnedOptionalTrialClockAdvanceSdkReplies {
    private OwnedOptionalTrialClockAdvanceSdkReplies() {}

    static void queue(Transport http, Authority a, String fault) {
        preflight(http, a);
        if (fault.equals("lost reply")) {
            http.disconnect();
            return;
        }
        http.reply(advancing(a, fault.equals("foreign target") ? a.trialEnd()+1 : a.trialEnd()));
        if (!fault.equals("foreign target")) {
            http.reply(ready(a, fault.equals("wrong ready") ? a.trialEnd()+1 : a.trialEnd()));
        }
    }

    static void preflight(Transport http, Authority a) {
        OwnedOptionalTrialSdkReplies.clockScopedObservation(http, a, "");
        http.reply(ready(a, a.frozen()));
    }

    static String ready(Authority a, long frozen) {
        return clock(a, frozen, "ready", new JsonObject());
    }

    static String advancing(Authority a, long target) {
        var detail = new JsonObject();
        var advancing = new JsonObject();
        advancing.addProperty("target_frozen_time", target);
        detail.add("advancing", advancing);
        return clock(a, a.frozen(), "advancing", detail);
    }

    private static String clock(Authority a, long frozen, String status, JsonObject detail) {
        var clock = new JsonObject();
        clock.addProperty("id", a.clockReference());
        clock.addProperty("object", "test_helpers.test_clock");
        clock.addProperty("livemode", false);
        clock.addProperty("name", a.clockName());
        clock.addProperty("frozen_time", frozen);
        clock.addProperty("created", a.clockCreated());
        clock.addProperty("deletes_after", a.clockDeletesAfter());
        clock.addProperty("status", status);
        clock.add("status_details", detail);
        return clock.toString();
    }
}
