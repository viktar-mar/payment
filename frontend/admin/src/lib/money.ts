import { code as currencyCode } from "currency-codes";

export function toMinorUnits(amount: string, currency: string): number {
  const metadata = currencyCode(currency);
  if (!/^\d+(?:\.\d+)?$/.test(amount) || !metadata)
    throw new Error("Enter a valid amount and currency");
  // ISO 4217 minor units from SIX metadata; locale display precision can differ.
  const digits = metadata.digits;
  const [whole, fraction = ""] = amount.split(".");
  const padded = fraction.padEnd(digits + 1, "0");
  // Decimal string + integer arithmetic; explicit half-up rounding at the currency's fraction boundary.
  const minor =
    BigInt(whole) * 10n ** BigInt(digits) +
    BigInt(padded.slice(0, digits) || "0") +
    (padded[digits] >= "5" ? 1n : 0n);
  if (minor <= 0n || minor > BigInt(Number.MAX_SAFE_INTEGER))
    throw new Error("Amount is outside the supported range");
  return Number(minor);
}
