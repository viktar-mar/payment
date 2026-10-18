import type {FormDiagnosticFrame,FormDiagnosticOutcome,FormDiagnosticSample} from './stripe-form-diagnostic-schema.mjs';
import type {FormDiagnosticHandoff,FormDiagnosticRuntime,FormDiagnosticOptions} from './stripe-form-diagnostic-runtime.mjs';
export interface FormDiagnosticLimits {readonly deadline:number;readonly signal:AbortSignal}
export interface FormDiagnosticDependencies {
  readonly createRuntime?:(options:FormDiagnosticOptions)=>Promise<FormDiagnosticRuntime>;
  readonly runBrowser:(handoff:FormDiagnosticHandoff,limits:FormDiagnosticLimits)=>Promise<{
    readonly samples:readonly FormDiagnosticSample[];readonly outcome:FormDiagnosticOutcome;readonly browserClosed:boolean;readonly contextClosed:boolean;
  }>;
}
export function runFormLoadDiagnostic(options:FormDiagnosticOptions,limits:FormDiagnosticLimits,dependencies:FormDiagnosticDependencies):Promise<{
  readonly frames:readonly FormDiagnosticFrame[];readonly exitCode:0|1;
}>;
