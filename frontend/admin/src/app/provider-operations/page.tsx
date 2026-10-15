import { ProviderOperationPanel } from '../../components/payments/ProviderOperationPanel';
import { DeadProviderReferences } from '../../components/payments/DeadProviderReferences';
export default function ProviderOperationsPage() {
  return <><h1 data-payment-heading tabIndex={-1}>Provider operations</h1><ProviderOperationPanel/><DeadProviderReferences/></>;
}
