"use client";
import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { api, errorTitle } from "../lib/api/client";
import type { components } from "../lib/api/schema";
export function LoginForm() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError("");
    try {
      const body: components["schemas"]["LoginRequest"] = {
        username,
        password,
      };
      await api("admin/v1/auth/sessions", { method: "POST", body });
      router.push("/sites");
      router.refresh();
    } catch (error) {
      setError(errorTitle(error));
    } finally {
      setPassword("");
      setPending(false);
    }
  }
  return (
    <form onSubmit={submit} className="surface form-stack max-w-md">
      <label>
        Username
        <input
          required
          maxLength={120}
          autoComplete="username"
          value={username}
          onChange={(event) => setUsername(event.target.value)}
        />
      </label>
      <label>
        Password
        <input
          required
          maxLength={1024}
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
      </label>
      {error && <p role="alert">{error}</p>}
      <button disabled={pending}>{pending ? "Signing in…" : "Sign in"}</button>
    </form>
  );
}
