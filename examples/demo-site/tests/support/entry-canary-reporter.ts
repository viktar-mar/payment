import type { Reporter, TestCase, TestResult, TestStep, FullResult } from '@playwright/test/reporter';
import { writeFileSync } from 'node:fs';
import { entryCanary } from './entry-canary';

// Inspect actual reporter callbacks internally; never serialize captured strings.
export default class EntryCanaryReporter implements Reporter {
  private records: object[] = [];
  private inspected = { stepEnd: 0, apiStepEnd: 0, apiErrors: 0, testEnd: 0, titleContainsPayload: 0, errorContainsPayload: 0 };
  onStepEnd(_test: TestCase, _result: TestResult, step: TestStep): void {
    ++this.inspected.stepEnd;
    if (step.category === 'pw:api') ++this.inspected.apiStepEnd;
    if (step.category === 'pw:api' && step.error) ++this.inspected.apiErrors;
    if (step.title.includes(entryCanary)) ++this.inspected.titleContainsPayload;
    if (JSON.stringify(step.error ?? {}).includes(entryCanary)) ++this.inspected.errorContainsPayload;
    if (!step.error) return;
    this.records.push({ channel: 'stepEnd', category: step.category, titleContainsPayload: step.title.includes(entryCanary), errorContainsPayload: JSON.stringify(step.error).includes(entryCanary) });
  }
  onTestEnd(test: TestCase, result: TestResult): void {
    ++this.inspected.testEnd;
    this.records.push({ channel: 'testEnd', test: test.title, status: result.status, errorContainsPayload: JSON.stringify(result.errors).includes(entryCanary) });
  }
  onEnd(result: FullResult): void {
    const summary = { status: result.status, inspected: this.inspected, records: this.records };
    const output = process.env.PAYMENT_ENTRY_CANARY_RESULT;
    if (!output) throw new Error('Canary evidence destination required');
    writeFileSync(output, JSON.stringify(summary, null, 2));
    console.log(JSON.stringify(summary));
  }
}
