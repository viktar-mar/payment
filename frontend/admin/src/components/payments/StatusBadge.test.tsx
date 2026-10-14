import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { StatusBadge } from "./StatusBadge";
it.each(["CREATED", "REQUIRES_PAYMENT_METHOD", "REQUIRES_ACTION", "PROCESSING", "SUCCEEDED", "FAILED", "CANCELLED", "PARTIALLY_REFUNDED", "REFUNDED", "UNKNOWN", "PENDING", "DELIVERED", "DEAD"])("renders labelled stable %s state", status => {
  render(<StatusBadge status={status} />); expect(screen.getByText(status)).toHaveClass(`status-${status.toLowerCase()}`);
});
it("keeps unknown backend state visible with neutral fallback", () => {
  render(<StatusBadge status="NEW_STATE" />); expect(screen.getByText("NEW_STATE")).toHaveClass("status-fallback");
});
