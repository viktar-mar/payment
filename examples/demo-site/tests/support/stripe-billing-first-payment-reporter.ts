import type {Reporter, TestCase, TestResult, FullResult} from '@playwright/test/reporter';
import {parseBillingFirstPaymentDiagnosticLine} from '../../../../infrastructure/stripe-billing-first-payment.mjs';
/** Fixed labels, one CLI authorization handoff and one validated final diagnostic. */
export default class FirstPaymentReporter implements Reporter {
  private buffer = '';
  private handoff = false;
  private diagnostic = false;
  private overflow = false;
  printsToStdio(): boolean {return true;}
  onStdOut(chunk: string | Buffer): void {
    for (const character of chunk.toString('utf8')) {
      if (character === '\n') {
        const line = this.overflow ? '' : this.buffer;
        this.buffer = '';
        this.overflow = false;
        if (!this.diagnostic && parseBillingFirstPaymentDiagnosticLine(line)) {
          this.diagnostic = true;
          process.stdout.write(line + '\n');
          continue;
        }
        const match = /^Authorize the isolated Sandbox CLI at (https:\/\/(?:access|dashboard)\.stripe\.com\/[^\s]*) using code ([A-Za-z0-9 -]{4,128})\. Keep this terminal open\.$/.exec(line);
        if (!this.handoff && match && !/(?:[spr]k_(?:test|live)_|whsec_|client_secret)/i.test(line)) {
          this.handoff = true;
          process.stdout.write(line + '\n');
        }
      } else if (!this.overflow && this.buffer.length < 4096) this.buffer += character;
      else {this.buffer = '';this.overflow = true;}
    }
  }
  onStdErr(): void {}
  onError(): void {process.stdout.write('first_payment_setup_failed\n');}
  onTestEnd(_test: TestCase, result: TestResult): void {
    process.stdout.write(result.status === 'passed' ? 'owned_initial_payment_passed\n' : 'owned_initial_payment_not_accepted\n');
  }
  onEnd(result: FullResult): void {
    this.buffer = '';
    process.stdout.write(result.status === 'passed' ? 'owned_initial_payment_result_passed full5b=NOT_ACCEPTED\n' : 'owned_initial_payment_result_not_accepted full5b=NOT_ACCEPTED\n');
  }
}
