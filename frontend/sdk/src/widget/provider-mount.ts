import type { CheckoutSession } from '../contracts.js';
import { PaymentSdkError } from '../errors.js';

export interface OwnedProviderMount {
  readonly host: HTMLDivElement;
  destroy(): void;
}
interface Lease extends OwnedProviderMount {
  current(): boolean;
}
const leases = new WeakMap<HTMLElement, Lease>();
let sequence = 0;

// Internal identity registry: DOM attributes select projection, never ownership.
export function ownedProviderHost(widget: HTMLElement): HTMLDivElement | undefined {
  const lease = leases.get(widget);
  return lease?.current() ? lease.host : undefined;
}

export function createProviderMount(widget: HTMLElement, anchor: HTMLElement, providerType: CheckoutSession['providerType']): OwnedProviderMount {
  const doc = widget.ownerDocument, root = widget.shadowRoot;
  const stripe = providerType === 'STRIPE';
  if (!root || anchor.getRootNode() !== root || !anchor.parentNode || !widget.isConnected
    || (stripe && (widget.getRootNode() !== doc || !doc.documentElement.contains(widget)))) throw new PaymentSdkError('INVALID_CONFIGURATION');
  leases.get(widget)?.destroy();
  const parent = anchor.parentNode;
  const host = doc.createElement('div');
  const wrapper = stripe ? doc.createElement('div') : host;
  wrapper.className = 'payment-element';
  const slot = stripe ? doc.createElement('slot') : undefined;
  if (slot) {
    slot.name = `gp-provider-${++sequence}`; host.slot = slot.name;
    wrapper.append(slot); widget.append(host);
  }
  anchor.before(wrapper);
  let retired = false;
  const protectedNodes: Node[] = slot ? [widget, host, wrapper, slot] : [widget, host];
  const revoke = () => { retired = true; observer.disconnect(); projectionObserver.disconnect(); };
  const inspect = (records: MutationRecord[], shadowProjection = false) => {
    for (const record of records) {
      if ((record.type === 'attributes' && (record.target === host || record.target === slot))
        // Observer provenance survives detached targets; their current root does not.
        || (stripe && shadowProjection
          && ((record.type === 'attributes' && (record.target as Element).localName === 'slot')
            || [...record.addedNodes, ...record.removedNodes].some(node => node.nodeType === 1 && ((node as Element).localName === 'slot' || (node as Element).querySelector('slot')))))
        || Array.from(record.removedNodes).some(node => protectedNodes.some(owned => node === owned || node.contains(owned)))) { revoke(); return; }
    }
  };
  const observer = new MutationObserver(records => inspect(records));
  const projectionObserver = new MutationObserver(records => inspect(records, true));
  observer.observe(doc, { childList: true, subtree: true });
  observer.observe(widget, { childList: true, subtree: true, attributes: true, attributeFilter: ['slot'] });
  projectionObserver.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['name'] });
  const lease: Lease = {
    host,
    current() {
      // Drain queued records before a positive answer, including detach/reinsert in this turn.
      inspect(observer.takeRecords());
      inspect(projectionObserver.takeRecords(), true);
      if (retired || leases.get(widget) !== lease) return false;
      if (!widget.isConnected || !host.isConnected || wrapper.parentNode !== parent || wrapper.getRootNode() !== root
        || (stripe ? host.parentElement !== widget || host.getRootNode() !== doc || !doc.documentElement.contains(host)
          || slot?.parentElement !== wrapper || host.assignedSlot !== slot || slot.assignedElements().length !== 1
          : host.getRootNode() !== root)) { revoke(); return false; }
      return true;
    },
    destroy() {
      revoke(); if (leases.get(widget) === lease) leases.delete(widget);
      host.remove(); slot?.remove(); if (wrapper !== host) wrapper.remove();
    },
  };
  leases.set(widget, lease);
  return Object.freeze({ host, destroy: () => lease.destroy() });
}
