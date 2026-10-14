"use client";
import { useEffect, useRef, useState } from "react";
import { api, errorTitle } from "../lib/api/client";
import type { components } from "../lib/api/schema";
import { CreateProductForm } from "./CreateProductForm";
import { CreatePriceForm } from "./CreatePriceForm";
import { PlanTransitionForm } from "./subscriptions/PlanTransitionForm";
type Catalog = components["schemas"]["CatalogResponse"];
type Price = components["schemas"]["PriceResponse"];
export function CatalogView({ siteId }: { siteId: string }) {
  const [catalog, setCatalog] = useState<Catalog>();
  const [error, setError] = useState("");
  const [confirm, setConfirm] = useState<Price>();
  const [pending, setPending] = useState(false);
  const cancel = useRef<HTMLButtonElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const priceHeading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    const controller = new AbortController();
    api<Catalog>(`admin/v1/sites/${siteId}/catalog`, {
      signal: controller.signal,
    })
      .then(setCatalog)
      .catch((error) => {
        if (!controller.signal.aborted) setError(errorTitle(error));
      });
    return () => controller.abort();
  }, [siteId]);
  useEffect(() => {
    if (!confirm) return;
    cancel.current?.focus();
    return () => {
      if (trigger.current?.isConnected) trigger.current.focus();
      else priceHeading.current?.focus();
    };
  }, [confirm]);
  async function publish() {
    if (!confirm?.id) return;
    setPending(true);
    setError("");
    try {
      const price = await api<Price>(`admin/v1/prices/${confirm.id}/publish`, {
        method: "POST",
      });
      setCatalog((current) => ({
        ...current,
        prices: current?.prices?.map((item) =>
          item.id === price.id ? price : item,
        ),
      }));
      setConfirm(undefined);
    } catch (error) {
      setError(errorTitle(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <div className="space-y-6">
      {error && !confirm && <p role="alert">{error}</p>}
      {!catalog && !error && <p role="status">Loading catalog…</p>}
      {catalog?.products?.length === 0 && <p>No products yet.</p>}
      {catalog && (
        <CreateProductForm
          siteId={siteId}
          onCreated={(product) =>
            setCatalog((current) => ({
              ...current,
              products: [...(current?.products ?? []), product],
            }))
          }
        />
      )}
      {catalog?.products?.map((product) => (
        <section className="surface" key={product.id}>
          <h2>{product.name}</h2>
          <p>
            {product.key} · {product.status}
          </p>
          <p>{product.description}</p>
          {product.id && <PlanTransitionForm siteId={siteId} productId={product.id} />}
          <ul className="space-y-3">
            {catalog.prices
              ?.filter((price) => price.product_id === product.id)
              .map((price) => {
                const hasPublishedVersion = catalog.prices?.some(
                  (other) =>
                    other.price_key === price.price_key &&
                    other.id !== price.id &&
                    other.status === "PUBLISHED",
                );
                return (
                  <li className="price-row" key={price.id}>
                    <h3 tabIndex={-1}>
                      {price.price_key} · v{price.version}
                    </h3>
                    <span
                      className={`badge status-${price.status?.toLowerCase()}`}
                    >
                      {price.status}
                    </span>
                    <p>
                      {price.amount_minor} minor units {price.currency} ·{" "}
                      {price.type} {price.billing_interval}
                    </p>
                    <p>
                      Trial: {price.trial_days} days · Tax: {price.tax_mode}{" "}
                      {price.tax_code}
                    </p>
                    {price.type === "SUBSCRIPTION" && <p>Trial payment method / Método de pagamento no teste: {price.trial_payment_method_policy} · Published Prices are immutable / Os preços publicados são imutáveis.</p>}
                    {price.provider_price_id && (
                      <p>Provider price: {price.provider_price_id}</p>
                    )}
                    {price.status === "DRAFT" && (
                      <>
                        <button
                          disabled={hasPublishedVersion}
                          aria-describedby={
                            hasPublishedVersion ? `publish-limit-${price.id}` : undefined
                          }
                          onClick={(event) => {
                            trigger.current = event.currentTarget;
                            priceHeading.current =
                              event.currentTarget
                                .closest("li")
                                ?.querySelector("h3") ?? null;
                            setConfirm(price);
                          }}
                        >
                          Publish
                        </button>
                        {hasPublishedVersion && (
                          <p id={`publish-limit-${price.id}`} className="hint">
                            Stage 1: a version with this price key is already published.
                            Publishing a replacement requires replacement and archive support in a later stage.
                          </p>
                        )}
                      </>
                    )}
                  </li>
                );
              })}
          </ul>
          {product.id && product.status === "ACTIVE" && (
            <CreatePriceForm
              productId={product.id}
              onCreated={(price) =>
                setCatalog((current) => ({
                  ...current,
                  prices: [...(current?.prices ?? []), price],
                }))
              }
            />
          )}
        </section>
      ))}
      {confirm && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="publish-title"
          className="dialog"
          onKeyDown={(event) => {
            if (event.key === "Escape" && !pending) setConfirm(undefined);
            if (event.key === "Tab") {
              const buttons =
                event.currentTarget.querySelectorAll<HTMLButtonElement>(
                  "button:not(:disabled)",
                );
              const first = buttons[0];
              const last = buttons[buttons.length - 1];
              if (!first) {
                event.preventDefault();
                return;
              }
              if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
              } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
              }
            }
          }}
        >
          <div className="surface">
            <h2 id="publish-title">
              Publish {confirm.price_key} v{confirm.version}?
            </h2>
            <p>
              This creates the provider price and makes this version available
              for payment.
            </p>
            {error && <p role="alert">{error}</p>}
            <button
              ref={cancel}
              disabled={pending}
              onClick={() => setConfirm(undefined)}
            >
              Cancel
            </button>{" "}
            <button disabled={pending} onClick={publish}>
              Confirm publish
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
