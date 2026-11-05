import type {TestCase, TestResult, FullResult} from '@playwright/test/reporter';
const {default:FirstPaymentReporter}=await import(new URL('./stripe-billing-first-payment-reporter.ts',import.meta.url).href) as typeof import('./stripe-billing-first-payment-reporter');
import {parseBillingFirstRenewalDiagnosticLine, parseBillingFirstRenewalCleanupDiagnosticLine} from '../../../../infrastructure/stripe-billing-first-payment.mjs';
/** Preserve the bounded login/output filter; report only this explicit combined scope. */
export default class FirstRenewalReporter extends FirstPaymentReporter {
  protected acceptDiagnostic(line: string): string | null {return parseBillingFirstRenewalDiagnosticLine(line);}
  protected acceptCleanupDiagnostic(line: string): string | null {return parseBillingFirstRenewalCleanupDiagnosticLine(line);}
  onError(): void {process.stdout.write('first_renewal_setup_failed\n');}
  onTestEnd(_test: TestCase, result: TestResult): void {
    process.stdout.write(result.status === 'passed' ? 'owned_initial_and_first_renewal_passed\n' : 'owned_initial_and_first_renewal_not_accepted\n');
  }
  onEnd(result: FullResult): void {
    this.buffer = '';
    process.stdout.write(result.status === 'passed' ? 'owned_initial_and_first_renewal_result_passed full5b=NOT_ACCEPTED\n' : 'owned_initial_and_first_renewal_result_not_accepted full5b=NOT_ACCEPTED\n');
  }
}
