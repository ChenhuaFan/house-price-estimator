import "@testing-library/jest-dom/vitest";
import { createElement } from "react";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

vi.mock("react-chartjs-2", () => ({
  Bar: (props: Record<string, unknown>) => createElement("canvas", { "data-chart": "bar", "aria-hidden": props["aria-hidden"] }),
  Line: (props: Record<string, unknown>) => createElement("canvas", { "data-chart": "line", "aria-hidden": props["aria-hidden"] }),
}));

afterEach(() => cleanup());

if (!globalThis.crypto.randomUUID) {
  Object.defineProperty(globalThis.crypto, "randomUUID", {
    configurable: true,
    value: () => "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  });
}
