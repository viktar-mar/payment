import type {FormDiagnosticOptions} from './stripe-form-diagnostic-runtime.mjs';
export interface ManualFormOptions extends FormDiagnosticOptions {readonly manualPort:number;}
export function manualFormOptIn(env:unknown):boolean;
export function validateManualFormOptions(value:unknown):Readonly<ManualFormOptions>|null;
export function validateFormDiagnosticTopology(value:unknown):Readonly<Omit<FormDiagnosticOptions,'env'>>|null;
