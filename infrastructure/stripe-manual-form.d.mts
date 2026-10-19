import type {ManualFormOptions} from './stripe-manual-form-options.mjs';
import type {FormDiagnosticLimits,ManualFormRuntime} from './stripe-form-diagnostic-runtime.mjs';
import type {ManualFormGateway,ManualFormGatewayOptions} from './stripe-manual-form-gateway.mjs';
import type {ManualFormReport} from './stripe-manual-form-schema.mjs';
export type {ManualFormOptions,ManualFormReport};
export interface ManualFormDependencies {
  readonly preflight?:(options:ManualFormOptions)=>Promise<unknown>;
  readonly createRuntime?:(options:ManualFormOptions)=>Promise<Readonly<ManualFormRuntime>>;
  readonly createGateway?:(options:ManualFormGatewayOptions,limits:FormDiagnosticLimits)=>Readonly<ManualFormGateway>;
  readonly now?:()=>number;
  readonly setTimeout?:(callback:()=>void,delay:number)=>unknown;
  readonly clearTimeout?:(handle:unknown)=>void;
  readonly write?:(text:string)=>void|Promise<void>;
  readonly subscribe?:(callback:()=>void)=>(()=>void);
}
export interface ManualFormResult {readonly report:Readonly<ManualFormReport>;readonly exitCode:0|1;}
export function runManualFormDiagnostic(options:ManualFormOptions,limits:FormDiagnosticLimits,dependencies?:ManualFormDependencies):Promise<Readonly<ManualFormResult>>;
export function runManualFormCli(input:{readonly env:unknown;readonly args:readonly string[]},dependencies?:ManualFormDependencies):Promise<Readonly<ManualFormResult>>;
