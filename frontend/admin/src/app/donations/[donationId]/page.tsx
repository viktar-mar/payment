import Link from "next/link";
import {DonationDetails} from "../../../components/donations/DonationDetails";
export default async function DonationPage({params}:{params:Promise<{donationId:string}>}){const {donationId}=await params;return <><Link href="/donations">← Donations</Link><DonationDetails key={donationId} donationId={donationId}/></>;}
