import type { components } from "../../../lib/api/schema";
export type Campaign = components["schemas"]["CampaignResponse"];
export type CampaignPage = components["schemas"]["CampaignPage"];
export type CampaignStatus = NonNullable<Campaign["status"]>;
