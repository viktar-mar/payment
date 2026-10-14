"use client";
import {useId, useState, type FormEvent} from "react";
import type {operations, components} from "../../lib/api/schema";
import {isUtcInstant} from "../../lib/utcInstant";
import {validUuid} from "../customers/context";
type GeneratedQuery = NonNullable<operations["listAdminDonations"]["parameters"]["query"]>;
export type DonationQuery = Omit<GeneratedQuery,"page"|"size"> & {page?:number;size?:number};
const fields = [["site_id","Site ID"],["campaign_id","Campaign ID"],["customer_id","Customer ID"],["donation_id","Donation ID"],["payment_id","Payment ID"],["created_from","Created from (UTC)"],["created_to","Created to (UTC)"]] as const;
const statuses:NonNullable<components["schemas"]["Projection"]["status"]>[]=["CREATED","REQUIRES_PAYMENT_METHOD","REQUIRES_ACTION","PROCESSING","SUCCEEDED","FAILED","CANCELLED","PARTIALLY_REFUNDED","REFUNDED"];
export function boundedDonationQuery(search:URLSearchParams):DonationQuery {
  const result:DonationQuery={};
  for(const [name] of fields){const value=search.get(name);if(value)result[name]=value;}
  const status=search.get("status");if(statuses.includes(status as typeof statuses[number]))result.status=status as typeof statuses[number];
  const bound=(name:string,min:number,max:number,fallback:number)=>{const value=search.get(name);return value!==null&&/^\d+$/.test(value)?Math.min(max,Math.max(min,Number(value))):fallback;};
  result.page=bound("page",0,100000,0);result.size=bound("size",1,50,20);return result;
}
export function donationQueryError(query:DonationQuery) {
  if([query.site_id,query.campaign_id,query.customer_id,query.donation_id,query.payment_id].some(value=>value&&!validUuid(value)))return "Enter valid UUID filters";
  for(const value of [query.created_from,query.created_to])if(value&&!isUtcInstant(value))return "Enter valid UTC instants ending in Z";
  if(query.created_from&&query.created_to&&Date.parse(query.created_from)>=Date.parse(query.created_to))return "Created from must be before created to";
  return "";
}
export function DonationFilters({query,onApply}:{query:DonationQuery;onApply:(next:DonationQuery)=>void}) {
  const id=useId();const [error,setError]=useState("");
  function submit(event:FormEvent<HTMLFormElement>){
    event.preventDefault();const params=new URLSearchParams();for(const [name,value] of new FormData(event.currentTarget).entries())if(typeof value==="string")params.set(name,value.trim());
    params.set("page","0");const next=boundedDonationQuery(params),problem=donationQueryError(next);setError(problem);if(!problem)onApply(next);
  }
  return <form className="surface filter-grid" aria-label="Donation filters" onSubmit={submit}>
    {fields.map(([name,label])=><label key={name}>{label}<input name={name} defaultValue={query[name]??""} maxLength={40} aria-describedby={error?id:undefined}/></label>)}
    <label>Status<select name="status" defaultValue={query.status??""}><option value="">All statuses</option>{statuses.map(status=><option key={status}>{status}</option>)}</select></label>
    <label>Page size<input name="size" type="number" min={1} max={50} defaultValue={query.size}/></label>
    <p>UTC range includes Created from and excludes Created to. Newest payments first. Server validation is authoritative.</p>
    {error&&<p role="alert" id={id}>{error}</p>}<button>Apply filters</button>
  </form>;
}
