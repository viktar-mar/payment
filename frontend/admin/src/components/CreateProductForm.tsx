"use client";
import { useState, type FormEvent } from "react";
import { api, errorTitle } from "../lib/api/client";
import type { components } from "../lib/api/schema";
export function CreateProductForm({
  siteId,
  onCreated,
}: {
  siteId: string;
  onCreated: (product: components["schemas"]["ProductResponse"]) => void;
}) {
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setPending(true);
    setError("");
    const body: components["schemas"]["CreateProductRequest"] = {
      key: String(data.get("key")),
      name: String(data.get("name")).trim(),
      description: String(data.get("description")),
    };
    try {
      onCreated(
        await api<components["schemas"]["ProductResponse"]>(
          `admin/v1/sites/${siteId}/products`,
          { method: "POST", body },
        ),
      );
      form.reset();
    } catch (error) {
      setError(errorTitle(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <form className="surface form-stack" onSubmit={submit}>
      <h2>Create product</h2>
      <label>
        Product key
        <input
          name="key"
          required
          pattern="[a-zA-Z0-9][a-zA-Z0-9\-]{2,62}"
          minLength={3}
          maxLength={63}
        />
      </label>
      <label>
        Product name
        <input name="name" required maxLength={120} />
      </label>
      <label>
        Description
        <textarea name="description" maxLength={1000} />
      </label>
      {error && <p role="alert">{error}</p>}
      <button disabled={pending}>
        {pending ? "Creating…" : "Create product"}
      </button>
    </form>
  );
}
