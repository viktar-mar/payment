import { supportsPaymentV2 } from './confirmation-v2.js';
import { mountStripePaymentAttempt, type StripePaymentAttemptHandle } from './stripe-payment-attempt-driver.js';
import type { SubscriptionAttemptStatus } from './contracts.js';
import type { GenericPaymentClient } from '../contracts.js';
import type { CustomerSession } from '../customer/contracts.js';
import { validateCustomerSession } from '../customer/contracts.js';
import { isAbort, PaymentSdkError, problemError } from '../errors.js';
import { SubscriptionFakeActionDriver, watchSubscriptionOperation } from './action-driver.js';
import { mountStripeSubscription, type StripeSubscriptionHandle } from './stripe-subscription-driver.js';
import { createProviderMount, ownedProviderHost, type OwnedProviderMount } from '../widget/provider-mount.js';
import type { SubscriptionAction, SubscriptionBillingPage, SubscriptionBillingView, SubscriptionEvent, SubscriptionHistory, SubscriptionMutation, SubscriptionOffer, SubscriptionOperation, SubscriptionQuote, SubscriptionTiming, SubscriptionView } from './contracts.js';
import { acceptManagerResult, type ManagerState } from './manager-state.js';
import { subscriptionCopy, type SubscriptionCopyKey } from './copy.js';
import { subscriptionStyles } from './styles.js';

export interface SubscriptionStateDetail { subscriptionId: string | null; operationId: string | null; state: string; revision: number; }
const ElementBase = (typeof HTMLElement === 'undefined' ? class {} : HTMLElement) as typeof HTMLElement;
// This private fingerprint never leaves memory. Tokens are not rendered or emitted.
const identity = (s?: CustomerSession): string => s ? JSON.stringify([s.apiBaseUrl, s.siteKey, s.siteId, s.origin, s.customer.id, s.customer.status, s.customer.maskedEmail, s.sessionToken, s.expiresAt, [...s.scopes].sort()]) : '';
const unresolved = (op: SubscriptionOperation | null): boolean => !!op && !['SUCCEEDED', 'FAILED'].includes(op.status);
const safeOperation = (op: SubscriptionOperation): SubscriptionOperation => ({ operationId: op.operationId, subscriptionId: op.subscriptionId, status: op.status, actionAvailable: op.actionAvailable });

export class GenericSubscriptionManager extends ElementBase {
  static observedAttributes = ['locale'];
  #client?: GenericPaymentClient;
  #session?: CustomerSession;
  #productId?: string;
  #priceId?: string;
  #locale: 'en' | 'pt' = 'en';
  #state: ManagerState = { authorityRevision: 0, phase: 'idle', subscription: null, operation: null };
  #fingerprint = '';
  #controller?: AbortController;
  #expiry?: ReturnType<typeof setTimeout>;
  #busy = false;
  #loaded = false;
  #uncertain = false;
  #action?: SubscriptionAction;
  #providerMount?: OwnedProviderMount;
  #stripeHandle?: StripeSubscriptionHandle | StripePaymentAttemptHandle;
  #v2?: { subscriptionId:string; operationId:string; attemptId?:string; status?:SubscriptionAttemptStatus; retrySent:boolean; attempted?:boolean };
  #formController?: AbortController;
  #formReady = false;
  #formOperation?: string;
  #spentOperations = new Set<string>();
  #polling = false;
  #offers: SubscriptionOffer[] = [];
  #billing: SubscriptionBillingPage = { items: [], nextCursor: null };
  #history: SubscriptionHistory = { items: [], nextCursor: null };
  #quote?: SubscriptionQuote;
  #cancelMode?: 'PERIOD_END' | 'IMMEDIATE';
  #error?: SubscriptionCopyKey;
  #section: HTMLElement;
  #title: HTMLHeadingElement;
  #simulation: HTMLParagraphElement;
  #status: HTMLParagraphElement;
  #alert: HTMLParagraphElement;
  #details: HTMLDListElement;
  #fieldset: HTMLFieldSetElement;
  #legend: HTMLLegendElement;
  #planLabel: HTMLLabelElement;
  #plan: HTMLSelectElement;
  #timingLabel: HTMLLabelElement;
  #timing: HTMLSelectElement;
  #consentLabel: HTMLLabelElement;
  #consent: HTMLInputElement;
  #quoteText: HTMLParagraphElement;
  #changeLabel: HTMLLabelElement;
  #changeConfirmed: HTMLInputElement;
  #cancelText: HTMLParagraphElement;
  #cancelLabel: HTMLLabelElement;
  #cancelConfirmed: HTMLInputElement;
  #billingTitle: HTMLHeadingElement;
  #billingList: HTMLUListElement;
  #historyTitle: HTMLHeadingElement;
  #historyList: HTMLUListElement;
  #protected: HTMLParagraphElement;
  #buttons = new Map<SubscriptionCopyKey, HTMLButtonElement>();

  get client(): GenericPaymentClient | undefined { return this.#client; }
  set client(value: GenericPaymentClient | undefined) { if (value !== this.#client) { this.#client = value; this.#configure(); } }
  get customerSession(): CustomerSession | undefined { return this.#session; }
  set customerSession(value: CustomerSession | undefined) {
    if (value !== this.#session || identity(value) !== this.#fingerprint) { this.#session = value; this.#configure(); }
  }
  get productId(): string | undefined { return this.#productId; }
  set productId(value: string | undefined) { if (value !== this.#productId) { this.#productId = value; this.#configure(); } }
  get priceId(): string | undefined { return this.#priceId; }
  set priceId(value: string | undefined) { if (value !== this.#priceId) { this.#priceId = value; this.#configure(); } }
  get locale(): 'en' | 'pt' { return this.#locale; }
  set locale(value: 'en' | 'pt') { this.#locale = value === 'pt' ? 'pt' : 'en'; this.#render(); }
  /** Safe state only; action capabilities are held separately. */
  get state(): Readonly<ManagerState> { return Object.freeze({ ...this.#state }); }

  constructor() {
    super(); const root = this.attachShadow({ mode: 'open' });
    const style = document.createElement('style'); style.textContent = subscriptionStyles;
    this.#section = document.createElement('section'); this.#title = document.createElement('h2'); this.#simulation = document.createElement('p');
    this.#status = document.createElement('p'); this.#status.setAttribute('role', 'status'); this.#status.setAttribute('aria-live', 'polite'); this.#status.tabIndex = -1;
    this.#alert = document.createElement('p'); this.#alert.id = 'subscription-error'; this.#alert.setAttribute('role', 'alert'); this.#alert.tabIndex = -1;
    this.#details = document.createElement('dl'); this.#fieldset = document.createElement('fieldset'); this.#legend = document.createElement('legend');
    this.#planLabel = document.createElement('label'); this.#planLabel.htmlFor = 'subscription-plan'; this.#plan = document.createElement('select'); this.#plan.id = 'subscription-plan'; this.#plan.setAttribute('aria-describedby', this.#alert.id);
    this.#timingLabel = document.createElement('label'); this.#timingLabel.htmlFor = 'subscription-timing'; this.#timing = document.createElement('select'); this.#timing.id = 'subscription-timing';
    [this.#consentLabel, this.#consent] = this.#checkbox('subscription-consent');
    [this.#changeLabel, this.#changeConfirmed] = this.#checkbox('subscription-confirm-change');
    [this.#cancelLabel, this.#cancelConfirmed] = this.#checkbox('subscription-confirm-cancel');
    this.#quoteText = document.createElement('p'); this.#cancelText = document.createElement('p');
    this.#billingTitle = document.createElement('h3'); this.#billingList = document.createElement('ul'); this.#historyTitle = document.createElement('h3'); this.#historyList = document.createElement('ul'); this.#protected = document.createElement('p'); this.#protected.className = 'note';
    this.#fieldset.append(this.#legend, this.#planLabel, this.#plan, this.#timingLabel, this.#timing, this.#consentLabel);
    this.#section.append(this.#title, this.#simulation, this.#status, this.#alert, this.#details, this.#fieldset, this.#quoteText, this.#changeLabel, this.#cancelText, this.#cancelLabel);
    const button = (key: SubscriptionCopyKey, act: () => void) => { const b = document.createElement('button'); b.type = 'button'; b.addEventListener('click', act); this.#buttons.set(key, b); this.#section.append(b); };
    button('refresh', () => { void this.#refresh(); });
    button('create', () => { if (!this.#canCreate() || !this.#requireConsent() || !this.#selectedOffer()) return; void this.#mutate(i => this.#client!.subscriptions.create({ session: i.session, signal: i.signal, priceId: this.#plan.value, recurringConsent: true, idempotencyKey: i.idempotencyKey })); });
    button('preview', () => { void this.#preview(); });
    button('apply', () => { if (!this.#quote || !this.#confirm(this.#changeConfirmed) || Date.parse(this.#quote.expiresAt) <= Date.now()) return; const quoteId = this.#quote.quoteId; void this.#mutate(i => this.#client!.subscriptions.change({ ...i, quoteId }), 'CHANGE_PLAN'); });
    button('cancelEnd', () => this.#openCancel('PERIOD_END'));
    button('cancelNow', () => this.#openCancel('IMMEDIATE'));
    button('submitCancel', () => { const mode = this.#cancelMode; if (!mode || !this.#confirm(this.#cancelConfirmed)) return; void this.#mutate(i => this.#client!.subscriptions.cancel({ ...i, mode, ...(mode === 'IMMEDIATE' ? { confirmation: i.subscriptionId } : {}) }), mode === 'IMMEDIATE' ? 'CANCEL_IMMEDIATE' : 'CANCEL_PERIOD_END'); });
    button('dismiss', () => { this.#quote = undefined; this.#cancelMode = undefined; this.#error = undefined; this.#changeConfirmed.checked = this.#cancelConfirmed.checked = false; this.#render(); });
    button('clear', () => { void this.#mutate(i => this.#client!.subscriptions.clearChange(i), 'CLEAR_CHANGE'); });
    button('withdraw', () => { void this.#mutate(i => this.#client!.subscriptions.withdrawCancellation(i), 'WITHDRAW_CANCELLATION'); });
    button('setup', () => { if (this.#requireConsent()) void this.#mutate(i => this.#client!.subscriptions.setupMethod({ ...i, recurringConsent: true }), 'SETUP_METHOD'); });
    button('recover', () => { const debt = this.#state.subscription?.debt; if (debt) void this.#mutate(i => this.#client!.subscriptions.recover({ ...i, billingRecordId: debt.billingRecordId }), 'RECOVER_INVOICE'); });
    button('resume', () => { if (this.#requireConsent()) void this.#mutate(i => this.#client!.subscriptions.resume({ ...i, recurringConsent: true }), 'RESUME'); });
    button('complete', () => { const action = this.#action, op = this.#state.operation; if (!action || !op) return; this.#action = undefined; void this.#mutate(i => new SubscriptionFakeActionDriver(this.#client!.subscriptions).complete(action, { ...i, operationId: op.operationId }), undefined, true); });
    button('pay', () => { void this.#pay(); });
    button('retryPayment', () => { void this.#retryPayment(); });
    button('continuePayment', () => { void this.#retryPayment(); });
    button('portal', () => { void this.#portal(); });
    this.#section.append(this.#billingTitle, this.#billingList); button('moreBilling', () => { void this.#more('billing'); });
    this.#section.append(this.#historyTitle, this.#historyList); button('moreHistory', () => { void this.#more('history'); }); this.#section.append(this.#protected);
    root.append(style, this.#section);
    this.#plan.addEventListener('change', () => { this.#quote = undefined; this.#changeConfirmed.checked = false; this.#error = undefined; this.#renderTimings(); this.#render(); });
    this.#timing.addEventListener('change', () => { this.#quote = undefined; this.#changeConfirmed.checked = false; this.#render(); });
    this.#render();
  }
  #checkbox(id: string): [HTMLLabelElement, HTMLInputElement] { const label = document.createElement('label'); label.htmlFor = id; const input = document.createElement('input'); input.type = 'checkbox'; input.id = id; input.setAttribute('aria-describedby', 'subscription-error'); label.append(input, document.createTextNode('')); return [label, input]; }
  connectedCallback(): void { this.#configure(); }
  disconnectedCallback(): void { this.#reset(); this.#render(); }
  attributeChangedCallback(name: string, old: string | null, value: string | null): void { if (name === 'locale' && old !== value) this.locale = value === 'pt' ? 'pt' : 'en'; }
  #reset(): void {
    this.#retireForm(); this.#v2=undefined; this.#spentOperations.clear(); this.#polling = false;
    this.#controller?.abort(); this.#controller = undefined; if (this.#expiry !== undefined) clearTimeout(this.#expiry); this.#expiry = undefined;
    this.#state = { authorityRevision: this.#state.authorityRevision + 1, phase: 'idle', subscription: null, operation: null };
    this.#fingerprint = identity(this.#session); this.#busy = this.#loaded = this.#uncertain = false; this.#action = undefined; this.#offers = []; this.#quote = undefined; this.#cancelMode = undefined; this.#error = undefined;
    this.#billing = { items: [], nextCursor: null }; this.#history = { items: [], nextCursor: null };
    this.#plan.replaceChildren(); this.#timing.replaceChildren(); this.#consent.checked = this.#changeConfirmed.checked = this.#cancelConfirmed.checked = false;
  }
  #configure(): void {
    this.#reset(); this.#render(); if (!this.isConnected || !this.#client || !this.#session || !this.#productId) return;
    try { supportsPaymentV2(this.#client.subscriptions); validateCustomerSession(this.#session); if (!this.#session.siteId || !this.#session.scopes.includes('SUBSCRIPTION_READ')) throw new PaymentSdkError('INVALID_SESSION'); }
    catch (e) { this.#state.phase = e instanceof PaymentSdkError && e.code === 'SESSION_EXPIRED' ? 'expired' : 'error'; this.#error = 'login'; this.#render(); return; }
    this.#controller = new AbortController(); const revision = this.#state.authorityRevision;
    const expire = () => { const left = Date.parse(this.#session!.expiresAt) - Date.now(); if (left > 0) { this.#expiry = setTimeout(expire, Math.min(left, 2147483647)); return; } this.#reset(); this.#state.phase = 'expired'; this.#render(); this.#emit(); };
    this.#expiry = setTimeout(expire, Math.min(Date.parse(this.#session.expiresAt) - Date.now(), 2147483647));
    void this.#refresh(revision);
  }
  #current(revision: number): boolean {
    if (revision !== this.#state.authorityRevision || !this.isConnected || this.#controller?.signal.aborted || !this.#session) return false;
    if (identity(this.#session) !== this.#fingerprint || Date.parse(this.#session.expiresAt) <= Date.now()) { this.#configure(); return false; }
    return true;
  }
  #emit(): void {
    const s = this.#state; const detail: SubscriptionStateDetail = Object.freeze({ subscriptionId: s.subscription?.subscriptionId ?? s.operation?.subscriptionId ?? null, operationId: s.operation?.operationId ?? null, state: s.phase, revision: s.authorityRevision });
    this.dispatchEvent(new CustomEvent('generic-subscription-state', { detail, bubbles: true, composed: true }));
  }
  #accept(revision: number, next: Partial<ManagerState>): void { if (!this.#current(revision)) return; this.#state = acceptManagerResult(this.#state, revision, { ...this.#state, ...next }); this.#render(); this.#emit(); }
  #failure(error: unknown, revision: number, mutation = false, preparation = false): void {
    if (!this.#current(revision) || isAbort(error)) return;
    this.#retireForm(); if(this.#v2)this.#v2.status=undefined; this.#action = undefined; this.#quote = undefined; this.#cancelMode = undefined;
    const code = problemError(error instanceof PaymentSdkError ? { code: error.code } : {}).code;
    if (['SESSION_EXPIRED', 'INVALID_SESSION', 'AUTHENTICATION_FAILED'].includes(code)) { this.#reset(); this.#state.phase = 'expired'; this.#error = 'login'; }
    else { if (mutation) this.#uncertain = true; this.#state.phase = preparation ? 'error' : mutation || unresolved(this.#state.operation) ? 'unknown' : 'error'; this.#error = mutation ? 'unknown' : 'error'; }
    this.#render(); this.#alert.focus(); this.#emit(); this.dispatchEvent(new CustomEvent('generic-subscription-error', { detail: Object.freeze({ code }), bubbles: true, composed: true }));
  }
  async #load(revision: number): Promise<void> {
    this.#loaded = false;
    const session = this.#session!, signal = this.#controller!.signal, client = this.#client!.subscriptions;
    const offers = await client.offers({ session, signal, productId: this.#productId! }); if (!this.#current(revision)) return;
    const subscriptions: SubscriptionView[] = []; let cursor: string | undefined; const cursors = new Set<string>();
    do { const page = await client.list({ session, signal, limit: 100, ...(cursor ? { cursor } : {}) }); if (!this.#current(revision)) return; subscriptions.push(...page.items.filter(s => s.productId === this.#productId)); cursor = page.nextCursor ?? undefined; if (cursor && cursors.has(cursor)) throw new PaymentSdkError('INVALID_RESPONSE'); if (cursor) cursors.add(cursor); } while (cursor);
    const active = subscriptions.filter(s => s.billingStatus !== 'CANCELLED'); if (active.length > 1) throw new PaymentSdkError('INVALID_RESPONSE');
    const subscription = active[0] ?? subscriptions.sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0] ?? null;
    const previous = this.#plan.value; this.#offers = offers; this.#plan.replaceChildren(...offers.map(o => { const option = document.createElement('option'); option.value = o.price.priceId; option.textContent = this.#price(o.price); return option; }));
    this.#plan.value = offers.some(o => o.price.priceId === previous) ? previous : this.#priceId ?? offers[0]?.price.priceId ?? ''; this.#renderTimings();
    if (subscription) {
      const input = { session, signal, subscriptionId: subscription.subscriptionId };
      const billing = await client.billingRecords(input); if (!this.#current(revision)) return;
      const history = await client.history(input); if (!this.#current(revision)) return;
      this.#billing = billing; this.#history = history; this.#loaded = true;
      this.#accept(revision, { subscription });
      const pointer = subscription.pendingOperation;
      if (pointer) { await this.#readOperation(revision, pointer.operationId, subscription.subscriptionId); return; }
    } else { this.#billing = { items: [], nextCursor: null }; this.#history = { items: [], nextCursor: null }; }
    this.#loaded = true; this.#action = undefined; this.#accept(revision, { subscription, phase: this.#uncertain ? 'unknown' : 'ready', operation: this.#uncertain ? this.#state.operation : null });
  }
  async #readOperation(revision: number, operationId: string, subscriptionId: string): Promise<void> {
    const input = { session: this.#session!, signal: this.#controller!.signal, subscriptionId, operationId }, client = this.#client!.subscriptions;
    const op = await client.operation(input); if (!this.#current(revision)) return;
    if (['SUCCEEDED','FAILED','UNKNOWN'].includes(op.status)) this.#retireForm();
    if (['SUCCEEDED','FAILED'].includes(op.status)) this.#v2=undefined;
    this.#action = undefined;
    if (!this.#v2 && ['PROCESSING','REQUIRES_ACTION'].includes(op.status) && this.#formOperation!==operationId && !this.#spentOperations.has(operationId) && this.#session!.scopes.includes('SUBSCRIPTION_MANAGE')) {
      let action: SubscriptionAction | undefined;
      try {
        action = await client.action(input); if (!this.#current(revision)) return;
      } catch (error) {
        if (isAbort(error) || error instanceof PaymentSdkError && ['INVALID_SESSION','SESSION_EXPIRED','AUTHENTICATION_FAILED'].includes(error.code)) throw error;
        // An unavailable original action is still pending; a read never retries a write.
        this.#error = 'processing';
      }
      if ((!action || action.kind==='PROVIDER_CLIENT') && supportsPaymentV2(client)) {
        const status=await client.currentConfirmationAttempt!(input); if(!this.#current(revision))return;
        if(status){this.#v2={subscriptionId,operationId,attemptId:status.attemptId,status,retrySent:false};action=undefined;}
      }
      if (action?.kind === 'PROVIDER_CLIENT') { this.#accept(revision, { operation:safeOperation(op),phase:this.#phase(op) }); if (!await this.#prepareStripe(revision,op,action)) return; }
      else if (action && op.status === 'REQUIRES_ACTION' && op.actionAvailable) this.#action = action;
    }
    this.#uncertain = op.status === 'UNKNOWN'; this.#accept(revision, { operation: safeOperation(op), phase: this.#phase(op) });
  }
  #retireForm(): void {
    const controller=this.#formController, handle=this.#stripeHandle, mount=this.#providerMount;
    this.#formReady=false; this.#formOperation=undefined; this.#formController=undefined; this.#stripeHandle=undefined; this.#providerMount=undefined;
    controller?.abort(); handle?.destroy(); mount?.destroy();
  }
  async #prepareStripe(revision:number, op:SubscriptionOperation, action?:SubscriptionAction, successor?:SubscriptionAttemptStatus):Promise<boolean> {
    if ((!successor && this.#spentOperations.has(op.operationId)) || this.#formOperation===op.operationId) return false;
    this.#retireForm(); this.#formOperation=op.operationId;
    const form = new AbortController(); this.#formController=form;
    try {
      const mount = createProviderMount(this,this.#buttons.get('pay')!,'STRIPE');
      // Reserve actual light-DOM geometry before the provider creates its iframe.
      mount.host.style.minHeight='3rem'; this.#providerMount=mount; this.#render();
      const client=this.#client!.subscriptions;
      const input={session:this.#session!,subscriptionId:op.subscriptionId,operationId:op.operationId,signal:AbortSignal.any([this.#controller!.signal,form.signal])};
      const options={client,input,container:mount.host,locale:this.#locale,
        isCurrent:()=>this.#current(revision) && this.#providerMount===mount && ownedProviderHost(this)===mount.host,
        onRetire:()=>{ if (this.#providerMount===mount) { this.#retireForm(); if(this.#current(revision)) this.#render(); } } };
      let handle:StripeSubscriptionHandle|StripePaymentAttemptHandle;
      if(supportsPaymentV2(client)){
        this.#v2 ??= {subscriptionId:op.subscriptionId,operationId:op.operationId,retrySent:false};
        const context=successor
          ? await client.paymentAttemptSession!({...input,attemptId:successor.attemptId,expiresAt:successor.expiresAt,idempotencyKey:crypto.randomUUID()})
          : await client.confirmationSession!({...input,action:action!,idempotencyKey:crypto.randomUUID()});
        if(!options.isCurrent()||input.signal.aborted)return false;
        if(context.protocol==='SERVER_CONFIRM_V2'){
          this.#v2={subscriptionId:op.subscriptionId,operationId:op.operationId,attemptId:context.attemptId,retrySent:false,status:{protocol:'SERVER_CONFIRM_V2',attemptId:context.attemptId,state:'READY',retryAllowed:false,successorAttemptId:null,expiresAt:context.expiresAt}};
          handle=await mountStripePaymentAttempt({...options,context});
        }else{this.#v2=undefined;handle=await mountStripeSubscription({...options,action:action!,selectedSession:context});}
      }else{handle=await mountStripeSubscription({...options,action:action!});}
      if (!this.#current(revision) || this.#providerMount!==mount) { handle.destroy(); return false; }
      this.#stripeHandle=handle; this.#formReady=true; this.#render(); return true;
    } catch(error) { if (this.#formController===form) this.#retireForm(); this.#failure(error,revision,false,true); return false; }
  }
  async #pay():Promise<void> {
    const revision=this.#state.authorityRevision, handle=this.#stripeHandle, op=this.#state.operation;
    if (this.#busy || !this.#formReady || !handle || !op || !this.#current(revision)) return;
    const v2='protocol' in handle && handle.protocol==='SERVER_CONFIRM_V2';
    if(!v2)this.#spentOperations.add(op.operationId);
    this.#formReady=false; this.#busy=true; this.#error=undefined; this.#render();
    let validationFailed=false;
    try { const result=await handle.confirm(); if(v2 && 'validationFailed' in result){validationFailed=true;if(this.#current(revision)){this.#formReady=true;this.#error='paymentDetails';}} }
    catch(error) { if (this.#current(revision) && !isAbort(error)) this.#error='unknown'; }
    finally { if (this.#current(revision)) {
      if(!validationFailed){if(v2&&this.#v2)this.#v2.attempted=true;this.#retireForm();if(v2){try{await this.#recoverAttempt(revision);}catch(error){this.#failure(error,revision);}}}
      if(this.#current(revision)){this.#busy=false;this.#render();if(!validationFailed)void this.#observe(revision,op);}
    } }
  }
  async #recoverAttempt(revision:number):Promise<void>{
    const locator=this.#v2;if(!locator)return;
    const input={session:this.#session!,signal:this.#controller!.signal,subscriptionId:locator.subscriptionId,operationId:locator.operationId};
    const client=this.#client!.subscriptions;
    const status=locator.attemptId?await client.confirmationAttempt!({...input,attemptId:locator.attemptId}):await client.currentConfirmationAttempt!(input);
    if(!this.#current(revision)||this.#v2!==locator)return;
    locator.status=status??undefined;if(status)locator.attemptId=status.attemptId;
    this.#error=status?.state==='DECLINED'?'paymentDeclined':status?.state==='UNKNOWN'?'unknown':undefined;
  }
  async #retryPayment():Promise<void>{
    const revision=this.#state.authorityRevision,locator=this.#v2,status=locator?.status;
    if(this.#busy||this.#polling||!locator||!status||!this.#current(revision)||Date.parse(status.expiresAt)<=Date.now()||!this.#session!.scopes.includes('SUBSCRIPTION_MANAGE'))return;
    if(status.state==='READY'&&locator.attempted)return;
    if(!status.successorAttemptId&&status.state!=='READY'&&(!status.retryAllowed||locator.retrySent))return;
    this.#busy=true;this.#error=undefined;this.#retireForm();this.#render();
    try{
      const client=this.#client!.subscriptions,input={session:this.#session!,signal:this.#controller!.signal,subscriptionId:locator.subscriptionId,operationId:locator.operationId};
      let prepared=status;
      if(!status.successorAttemptId&&status.state!=='READY'){
        locator.retrySent=true;
        prepared=await client.retryPaymentAttempt!({...input,attemptId:status.attemptId,idempotencyKey:crypto.randomUUID()});
        if(!this.#current(revision)||this.#v2!==locator)return;
        locator.status=prepared;
        if(prepared.state!=='DECLINED'||!prepared.successorAttemptId)throw new PaymentSdkError('INVALID_RESPONSE');
      }
      const attemptId=prepared.successorAttemptId??prepared.attemptId;
      const successor=await client.confirmationAttempt!({...input,attemptId});
      if(!this.#current(revision)||this.#v2!==locator)return;
      if(successor.state!=='READY'||Date.parse(successor.expiresAt)<=Date.now())throw new PaymentSdkError('INVALID_RESPONSE');
      const op=this.#state.operation??{subscriptionId:locator.subscriptionId,operationId:locator.operationId,status:'REQUIRES_ACTION' as const,actionAvailable:true};
      this.#accept(revision,{operation:op});await this.#prepareStripe(revision,op,undefined,successor);
    }catch(error){this.#failure(error,revision,true);}
    finally{if(this.#current(revision)){this.#busy=false;this.#render();}}
  }
  async #observe(revision:number, original:SubscriptionOperation):Promise<void> {
    if (!this.#current(revision) || this.#polling) return; this.#polling=true; this.#render();
    try {
      for await (const op of watchSubscriptionOperation(this.#client!.subscriptions,{session:this.#session!,signal:this.#controller!.signal,subscriptionId:original.subscriptionId,operationId:original.operationId})) {
        if (!this.#current(revision)) return;
        this.#uncertain=op.status==='UNKNOWN'; this.#accept(revision,{operation:safeOperation(op),phase:this.#phase(op)});
        if (['SUCCEEDED','FAILED'].includes(op.status)) { this.#v2=undefined; this.#error=undefined; this.#consent.checked=false; await this.#load(revision); }
      }
    } catch(error) { this.#failure(error,revision); }
    finally { if (this.#current(revision)) { this.#polling=false; this.#render(); } }
  }
  #phase(op: SubscriptionOperation): ManagerState['phase'] { return op.status === 'UNKNOWN' ? 'unknown' : op.status === 'REQUIRES_ACTION' ? 'requires_action' : ['RESERVED', 'PROCESSING'].includes(op.status) ? 'processing' : op.status === 'FAILED' ? 'error' : 'ready'; }
  async #refresh(revision = this.#state.authorityRevision): Promise<void> {
    if (this.#busy || this.#polling || !this.#current(revision) || !this.#controller) return;
    this.#busy = true; this.#error = undefined; this.#quote = undefined; this.#cancelMode = undefined;
    this.#changeConfirmed.checked = this.#cancelConfirmed.checked = false; this.#render();
    try {
      // A just-created operation can precede the list projection. Keep its original
      // pointer until a status read establishes a terminal result; never re-create.
      const operation = this.#state.operation;
      if (unresolved(operation)) {
        await this.#readOperation(revision, operation!.operationId, operation!.subscriptionId);
        if (this.#current(revision) && !unresolved(this.#state.operation)) await this.#load(revision);
      } else await this.#load(revision);
      if(this.#current(revision)&&this.#v2)await this.#recoverAttempt(revision);
    } catch (e) { this.#failure(e, revision); }
    finally { if (this.#current(revision)) { this.#busy = false; this.#render(); } }
  }
  #blocked(): boolean { const pointer = this.#state.subscription?.pendingOperation, operation = this.#state.operation; return this.#uncertain || unresolved(operation) || !!pointer && (!operation || operation.operationId !== pointer.operationId || unresolved(operation)); }
  #canCreate(): boolean { const s = this.#state.subscription; return this.#loaded && !!this.#controller && !!this.#session?.scopes.includes('SUBSCRIPTION_MANAGE') && (!s || s.billingStatus === 'CANCELLED') && !this.#blocked(); }
  #can(action: SubscriptionView['permittedActions'][number]): boolean { return this.#loaded && !!this.#session?.scopes.includes('SUBSCRIPTION_MANAGE') && !!this.#state.subscription?.permittedActions.includes(action) && !this.#blocked(); }
  #requireConsent(): boolean { if (this.#consent.checked) return true; this.#error = 'consentError'; this.#render(); this.#consent.focus(); return false; }
  #confirm(input: HTMLInputElement): boolean { if (input.checked) return true; this.#error = 'confirmationError'; this.#render(); input.focus(); return false; }
  #selectedOffer(): SubscriptionOffer | undefined { const offer = this.#offers.find(o => o.price.priceId === this.#plan.value); if (!offer) { this.#error = 'invalidPlan'; this.#render(); this.#plan.focus(); } return offer; }
  #mutationInput(): SubscriptionMutation { return { session: this.#session!, signal: this.#controller!.signal, subscriptionId: this.#state.subscription?.subscriptionId ?? this.#state.operation?.subscriptionId ?? '', idempotencyKey: crypto.randomUUID() }; }
  async #mutate(call: (input: SubscriptionMutation) => Promise<SubscriptionOperation>, permission?: SubscriptionView['permittedActions'][number], completing = false): Promise<void> {
    const revision = this.#state.authorityRevision;
    if (this.#busy || !this.#current(revision) || !this.#session!.scopes.includes('SUBSCRIPTION_MANAGE') || (permission && !this.#can(permission)) || (!completing && (this.#uncertain || unresolved(this.#state.operation)))) return;
    this.#busy = true; this.#error = undefined; this.#quote = undefined; this.#cancelMode = undefined; this.#action = undefined; this.#accept(revision, { phase: 'processing' });
    let outcomeKnown = false;
    try {
      const op = await call(this.#mutationInput()); if (!this.#current(revision)) return;
      outcomeKnown = true;
      this.#action = op.nextAction; this.#uncertain = op.status === 'UNKNOWN';
      this.#accept(revision, { operation: safeOperation(op), phase: this.#phase(op) });
      if (this.#action?.kind==='PROVIDER_CLIENT') { const action=this.#action; this.#action=undefined; if (!await this.#prepareStripe(revision,op,action)) return; }
      else if (['PROCESSING','REQUIRES_ACTION'].includes(op.status) && !this.#action) await this.#readOperation(revision, op.operationId, op.subscriptionId);
      if (['SUCCEEDED', 'FAILED'].includes(op.status)) { this.#consent.checked = false; await this.#load(revision); }
      if (this.#current(revision)) this.#status.focus();
    } catch (e) { this.#failure(e, revision, !outcomeKnown); }
    finally { if (this.#current(revision)) { this.#busy = false; this.#render(); } }
  }
  async #preview(): Promise<void> {
    const revision = this.#state.authorityRevision, offer = this.#selectedOffer(); if (!offer || this.#busy || !this.#current(revision) || !this.#can('CHANGE_PLAN') || !offer.allowedTimings.includes(this.#timing.value as SubscriptionTiming)) return;
    this.#busy = true; this.#quote = undefined; this.#changeConfirmed.checked = false; this.#error = undefined; this.#render();
    try { const quote = await this.#client!.subscriptions.preview({ session: this.#session!, signal: this.#controller!.signal, subscriptionId: this.#state.subscription!.subscriptionId, targetPriceId: offer.price.priceId, timing: this.#timing.value as SubscriptionTiming }); if (!this.#current(revision)) return; this.#quote = quote; }
    catch (e) { this.#failure(e, revision); } finally { if (this.#current(revision)) { this.#busy = false; this.#render(); if (this.#quote) this.#changeConfirmed.focus(); } }
  }
  #openCancel(mode: 'PERIOD_END' | 'IMMEDIATE'): void { if (this.#busy || !this.#can(mode === 'IMMEDIATE' ? 'CANCEL_IMMEDIATE' : 'CANCEL_PERIOD_END')) return; this.#cancelMode = mode; this.#quote = undefined; this.#cancelConfirmed.checked = false; this.#error = undefined; this.#render(); this.#cancelConfirmed.focus(); }
  async #portal(): Promise<void> {
    const revision = this.#state.authorityRevision; if (this.#busy || !this.#current(revision) || !this.#can('OPEN_PORTAL')) return;
    this.#busy = true; this.#action = undefined; this.#render();
    try { const url = new URL(location.href); url.searchParams.delete('subscriptionPortal'); url.searchParams.delete('operationId'); const portal = await this.#client!.subscriptions.portal({ ...this.#mutationInput(), returnUrl: url.href }); if (!this.#current(revision)) return; location.assign(portal.navigationUrl); }
    catch (e) { this.#failure(e, revision); } finally { if (this.#current(revision)) { this.#busy = false; this.#render(); } }
  }
  async #more(kind: 'billing' | 'history'): Promise<void> {
    const revision = this.#state.authorityRevision, cursor = kind === 'billing' ? this.#billing.nextCursor : this.#history.nextCursor;
    if (!cursor || this.#busy || !this.#current(revision) || !this.#state.subscription) return; this.#busy = true; this.#render();
    try { const input = { session: this.#session!, signal: this.#controller!.signal, subscriptionId: this.#state.subscription.subscriptionId, cursor };
      if (kind === 'billing') { const page = await this.#client!.subscriptions.billingRecords(input); if (!this.#current(revision)) return; this.#billing = { items: [...this.#billing.items, ...page.items], nextCursor: page.nextCursor }; }
      else { const page = await this.#client!.subscriptions.history(input); if (!this.#current(revision)) return; this.#history = { items: [...this.#history.items, ...page.items], nextCursor: page.nextCursor }; }
    } catch (e) { this.#failure(e, revision); } finally { if (this.#current(revision)) { this.#busy = false; this.#render(); } }
  }
  #money(amount: number, currency: string): string { const format = new Intl.NumberFormat(this.#locale, { style: 'currency', currency }); return format.format(amount / 10 ** format.resolvedOptions().maximumFractionDigits!); }
  #price(p: SubscriptionOffer['price']): string { const t = subscriptionCopy[this.#locale]; return `${p.priceKey} · ${this.#money(p.amountMinor, p.currency)} / ${p.interval === 'MONTH' ? t.month : t.year}`; }
  #renderTimings(): void { const old = this.#timing.value; const timings = this.#offers.find(o => o.price.priceId === this.#plan.value)?.allowedTimings ?? []; this.#timing.replaceChildren(...timings.map(value => { const option = document.createElement('option'); option.value = value; return option; })); this.#timing.value = timings.includes(old as SubscriptionTiming) ? old : timings[0] ?? ''; }
  #render(): void {
    const t = subscriptionCopy[this.#locale], s = this.#state.subscription, op = this.#state.operation, authenticated = !!this.#session && !!this.#client && !!this.#controller, canCreate = authenticated && this.#canCreate();
    this.#section.lang = this.#locale; this.#section.setAttribute('aria-label', t.title); this.#section.setAttribute('aria-busy', String(this.#busy)); this.#title.textContent = t.title; this.#simulation.textContent = t.simulated; this.#simulation.hidden=!this.#action?.kind.startsWith('FAKE_');
    this.#status.textContent = this.#state.phase === 'expired' ? t.expired : !authenticated ? t.login : this.#busy ? t.loading : this.#state.phase === 'unknown' ? t.unknown : this.#state.phase === 'processing' ? t.processing : this.#state.phase === 'requires_action' ? t.requiresAction : this.#state.phase === 'error' ? t.error : s ? t.ready : t.idle;
    this.#alert.hidden = !this.#error; this.#alert.textContent = this.#error ? t[this.#error] : '';
    const rows: [string, string][] = [];
    if (s) {
      rows.push([t.state, `${s.state} · ${s.billingStatus}`], [t.billingTier, this.#price(s.billingPrice)], [t.entitledTier, s.entitledPrice ? this.#price(s.entitledPrice) : t.none], [t.access, `${s.access.allowed ? t.allowed : t.denied} · ${s.access.reason}`], [t.period, s.period ? `${s.period.start} – ${s.period.end}` : t.none], [t.paidThrough, s.paidThrough ?? t.none], [t.trial, s.trial ? `${s.trial.start} – ${s.trial.end}` : t.none], [t.grace, s.debt?.graceDeadline ?? t.none], [t.cancellation, s.cancellation?.effectiveAt ?? t.none], [t.pendingChange, s.pendingChange ? `${this.#price(s.pendingChange.targetPrice)} · ${s.pendingChange.effectiveAt} · ${s.pendingChange.state}` : t.none], [t.nextAmount, s.nextAmount ? `${this.#money(s.nextAmount.amountMinor, s.nextAmount.currency)} · ${s.nextAmount.taxFinal ? t.finalTax : t.estimate}` : t.none]);
    }
    if (op) rows.push([t.operation, `${op.operationId} · ${op.status}`]);
    this.#details.replaceChildren(...rows.flatMap(([label, value]) => { const dt = document.createElement('dt'), dd = document.createElement('dd'); dt.textContent = label; dd.textContent = value; return [dt, dd]; }));
    this.#fieldset.hidden = !authenticated || !(canCreate || this.#can('CHANGE_PLAN') || this.#can('SETUP_METHOD') || this.#can('RESUME'));
    this.#fieldset.disabled = this.#busy; this.#legend.textContent = t.options; this.#planLabel.textContent = t.plan; this.#timingLabel.textContent = t.timing;
    this.#plan.hidden = this.#planLabel.hidden = !(canCreate || this.#can('CHANGE_PLAN')); this.#timing.hidden = this.#timingLabel.hidden = !this.#can('CHANGE_PLAN');
    for (const option of this.#plan.options) { const offer = this.#offers.find(o => o.price.priceId === option.value); if (offer) option.textContent = this.#price(offer.price); }
    for (const option of this.#timing.options) option.textContent = option.value === 'IMMEDIATE' ? t.immediate : t.nextPeriod;
    this.#consentLabel.lastChild!.textContent = t.consent; this.#changeLabel.lastChild!.textContent = t.confirmChange; this.#cancelLabel.lastChild!.textContent = t.confirmCancel;
    this.#consent.setAttribute('aria-invalid', String(this.#error === 'consentError')); this.#plan.setAttribute('aria-invalid', String(this.#error === 'invalidPlan'));
    this.#quoteText.hidden = this.#changeLabel.hidden = !this.#quote; this.#changeConfirmed.disabled = this.#busy;
    const q = this.#quote; this.#quoteText.textContent = q ? `${t.quote}: ${this.#price(q.targetPrice)} · ${t.dueNow}: ${this.#money(q.dueNowMinor, q.currency)} · ${t.nextAmount}: ${this.#money(q.nextAmountMinor, q.currency)} · ${t.effective}: ${q.effectiveAt} · ${t.quoteExpires}: ${q.expiresAt} · ${q.taxFinal ? t.finalTax : t.estimate}` : '';
    this.#cancelText.hidden = this.#cancelLabel.hidden = !this.#cancelMode; this.#cancelConfirmed.disabled = this.#busy;
    this.#cancelText.textContent = this.#cancelMode ? `${t.effective}: ${this.#cancelMode === 'PERIOD_END' ? s?.period?.end ?? t.none : t.immediate}. ${t.cancelNote}` : '';
    const visible: Partial<Record<SubscriptionCopyKey, boolean>> = { refresh: authenticated, create: canCreate, preview: this.#can('CHANGE_PLAN'), apply: !!q && this.#can('CHANGE_PLAN'), cancelEnd: this.#can('CANCEL_PERIOD_END'), cancelNow: this.#can('CANCEL_IMMEDIATE'), submitCancel: !!this.#cancelMode, dismiss: !!q || !!this.#cancelMode, clear: this.#can('CLEAR_CHANGE'), withdraw: this.#can('WITHDRAW_CANCELLATION'), setup: this.#can('SETUP_METHOD'), recover: this.#can('RECOVER_INVOICE') && !!s?.debt, resume: this.#can('RESUME'), complete: authenticated && !!this.#action && op?.status === 'REQUIRES_ACTION', portal: this.#can('OPEN_PORTAL'), moreBilling: authenticated && !!this.#billing.nextCursor, moreHistory: authenticated && !!this.#history.nextCursor };
    visible.pay=authenticated && !!this.#providerMount;
    const attempt=this.#v2?.status,live=!!attempt&&Date.parse(attempt.expiresAt)>Date.now()&&!!this.#session?.scopes.includes('SUBSCRIPTION_MANAGE')&&!this.#providerMount;
    visible.retryPayment=authenticated&&live&&attempt?.retryAllowed===true&&!attempt.successorAttemptId&&!this.#v2?.retrySent;
    visible.continuePayment=authenticated&&live&&!!(attempt?.successorAttemptId||attempt?.state==='READY'&&!this.#v2?.attempted);
    if(!this.#busy&&attempt?.state==='DECLINED')this.#status.textContent=t.paymentDeclined;
    for (const [key, button] of this.#buttons) { button.textContent = t[key]; button.hidden = !visible[key]; button.disabled = this.#busy || (key==='pay' && !this.#formReady) || (key==='refresh' && this.#polling); }
    const list = (element: HTMLUListElement, items: string[]) => element.replaceChildren(...(items.length ? items : [t.noRecords]).map(text => { const li = document.createElement('li'); li.textContent = text; return li; }));
    this.#billingTitle.textContent = t.billing; this.#historyTitle.textContent = t.history;
    list(this.#billingList, this.#billing.items.map((b: SubscriptionBillingView) => `${b.kind} · ${this.#money(b.amountDueMinor, b.currency)} · ${b.settlement} · ${b.period.start} – ${b.period.end}`));
    list(this.#historyList, this.#history.items.map((e: SubscriptionEvent) => `${e.type} · ${e.occurredAt}`)); this.#protected.textContent = t.protected;
  }
}
/** Explicit SSR-safe registration; importing the SDK never registers an element. */
export function registerGenericSubscriptionManager(tagName = 'generic-subscription-manager'): void {
  if (typeof customElements === 'undefined' || customElements.get(tagName)) return;
  // A constructor may only be registered once; a subclass allows additional explicit tags.
  customElements.define(tagName, class extends GenericSubscriptionManager {});
}
