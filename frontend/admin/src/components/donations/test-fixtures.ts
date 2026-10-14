export const donationId = "10000000-0000-0000-0000-000000000001";
export const siteId = "20000000-0000-0000-0000-000000000002";
export const campaignId = "30000000-0000-0000-0000-000000000003";
export const customerId = "40000000-0000-0000-0000-000000000004";
export const paymentId = "50000000-0000-0000-0000-000000000005";
export const donation = { donation_id: donationId, site_id: siteId, campaign_id: campaignId, campaign_key: "help-now", campaign_version: 2,
  payment_id: paymentId, customer_id: customerId, amount_minor: 1200, currency: "EUR", status: "SUCCEEDED" as const,
  message_state: "PRESENT" as const, has_message: true, created_at: "2026-10-10T10:00:00Z", updated_at: "2026-10-10T11:00:00Z" };
export const page = {items: [donation], page: 0, size: 20, total: 1};
