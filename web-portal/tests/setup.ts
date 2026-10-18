import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

afterEach(() => cleanup());

if (!globalThis.crypto.randomUUID) {
  Object.defineProperty(globalThis.crypto, "randomUUID", {
    configurable: true,
    value: () => "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  });
}
