import Link from "next/link";
import { PaymentDetails } from "../../../components/payments/PaymentDetails";
export default async function PaymentPage({ params }: { params: Promise<{ paymentId: string }> }) { const { paymentId } = await params; return <><Link href="/payments">← Payments</Link><PaymentDetails key={paymentId} paymentId={paymentId} /></>; }
