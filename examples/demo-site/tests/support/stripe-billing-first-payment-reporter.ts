import type {Reporter, TestCase, TestResult, FullResult} from '@playwright/test/reporter';
/** Fixed labels only; CLI authorization handoff is the sole permitted transient text. */
export default class FirstPaymentReporter implements Reporter {
  private buffer = '';
  private handoff = false;
  printsToStdio(): boolean {return true;}
  onStdOut(chunk: string | Buffer): void {
    if (this.handoff) return;
    for (const character of chunk.toString('utf8')) {
      if (character === '\n') {
        const line = this.buffer;
        this.buffer = '';
        const match = /^Authorize the isolated Sandbox CLI at (https:\/\/(?:access|dashboard)\.stripe\.com\/[^\s]*) using code ([A-Za-z0-9 -]{4,128})\. Keep this terminal open\.$/.exec(line);
        if (match && !/(?:[spr]k_(?:test|live)_|whsec_|client_secret)/i.test(line)) {
          this.handoff = true;
          process.stdout.write(line + '\n');
        }
      } else if (this.buffer.length < 4096) this.buffer += character;
      else this.buffer = '';
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
