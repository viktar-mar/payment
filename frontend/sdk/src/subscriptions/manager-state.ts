import type { SubscriptionOperation, SubscriptionView } from './contracts.js';
export interface ManagerState {
  authorityRevision: number;
  phase: 'idle' | 'loading' | 'ready' | 'processing' | 'requires_action' | 'unknown' | 'expired' | 'error';
  subscription: SubscriptionView | null;
  operation: SubscriptionOperation | null;
}
export function acceptManagerResult(state: ManagerState, authorityRevision: number, next: ManagerState): ManagerState {
  return authorityRevision === state.authorityRevision ? { ...next, authorityRevision: state.authorityRevision } : state;
}
