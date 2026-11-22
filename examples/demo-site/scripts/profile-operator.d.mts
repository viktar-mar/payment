export interface ProfileOperator {
  readonly url:string;
  wait(action:'initial'|'method',limits:{deadline:number;signal:AbortSignal}):Promise<void>;
  close():Promise<void>;
}
export function openProfileOperator(options:{rehearsal:boolean;onAbandon?:()=>void;handoffPath?:string}):Promise<ProfileOperator>;
export function writeProfileHandoff(path:string,operator:ProfileOperator):Promise<void>;
