import { afterEach, vi } from "vitest";
import "@testing-library/jest-dom/vitest";
afterEach(() => { if (typeof document !== 'undefined') document.body.replaceChildren(); if (typeof sessionStorage !== 'undefined') sessionStorage.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });
