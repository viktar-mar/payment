import { expect, it } from "vitest";
import { toMinorUnits } from "./money";
it.each([
  ["1.005", "USD", 101],
  ["1.004", "USD", 100],
  ["100.5", "JPY", 101],
  ["1.2345", "KWD", 1235],
  ["0.01", "EUR", 1],
  ["1.23", "AFN", 123],
  ["1.23", "IQD", 1230],
])("rounds %s %s half-up to %i minor units", (amount, currency, expected) => {
  expect(toMinorUnits(amount, currency)).toBe(expected);
});
it.each(["", "-1", "1e3", "NaN", "0", "9007199254740992"])(
  "rejects invalid or unsafe money %s",
  (amount) => {
    expect(() => toMinorUnits(amount, "USD")).toThrow();
  },
);
