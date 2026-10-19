import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MotionProvider } from "@/components/motion/motion-provider";
import { EstimatorWorkspace } from "@/features/estimator/estimator-workspace";
import { MarketDashboard } from "@/features/market/market-dashboard";
import { estimate, marketPageModel } from "@/tests/fixtures";

const estimatorApi = vi.hoisted(() => ({ create: vi.fn(), load: vi.fn(), remove: vi.fn(), clear: vi.fn() }));
const marketApi = vi.hoisted(() => ({ loadDashboard: vi.fn(), createExport: vi.fn(), getExport: vi.fn(), cancelExport: vi.fn(), runWhatIf: vi.fn() }));

vi.mock("@/features/estimator/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/features/estimator/api")>();
  return { ...actual, createEstimate: estimatorApi.create, loadHistory: estimatorApi.load, deleteEstimate: estimatorApi.remove, clearEstimateHistory: estimatorApi.clear };
});
vi.mock("@/features/market/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/features/market/api")>();
  return { ...actual, loadMarketDashboard: marketApi.loadDashboard, createExport: marketApi.createExport, getExport: marketApi.getExport, cancelExport: marketApi.cancelExport, runWhatIf: marketApi.runWhatIf };
});

describe("smart screen containers", () => {
  beforeEach(() => {
    Object.values(estimatorApi).forEach((mock) => mock.mockReset());
    Object.values(marketApi).forEach((mock) => mock.mockReset());
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => { callback(0); return 1; });
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });

  it("coordinates estimator mutations from server-provided history", async () => {
    const user = userEvent.setup();
    const first = estimate();
    const second = estimate("33333333-3333-4333-8333-333333333333");
    const created = estimate("55555555-5555-4555-8555-555555555555");
    estimatorApi.create.mockResolvedValue(created);
    estimatorApi.load.mockResolvedValue({ items: [created], next_cursor: null });
    estimatorApi.remove.mockResolvedValue(undefined);
    estimatorApi.clear.mockResolvedValue(undefined);
    render(<MotionProvider><EstimatorWorkspace initialModel={{ history: { items: [first, second], next_cursor: "next" }, warning: null }} /></MotionProvider>);

    await user.click(screen.getAllByRole("button", { name: "View" })[0]!);
    expect(screen.getByRole("heading", { name: "Estimated property value" })).toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "Use as scenario" })[0]!);
    expect(screen.getByLabelText(/Interior floor area/)).toHaveValue(first.inputs.square_footage);
    await user.click(screen.getByRole("button", { name: "Family house" }));
    await user.click(screen.getByRole("button", { name: "Estimate value" }));
    await waitFor(() => expect(estimatorApi.create).toHaveBeenCalledOnce());
    expect(screen.getByRole("status")).toHaveTextContent("Estimate created");

    await user.click(screen.getByRole("button", { name: "Load more history" }));
    await waitFor(() => expect(estimatorApi.load).toHaveBeenCalledWith("next"));
    await user.click(screen.getAllByRole("button", { name: "Delete" })[0]!);
    await waitFor(() => expect(estimatorApi.remove).toHaveBeenCalled());
    await user.click(screen.getByRole("button", { name: "Clear history" }));
    await waitFor(() => expect(estimatorApi.clear).toHaveBeenCalledOnce());
    expect(screen.getByText("No estimates yet")).toBeInTheDocument();
  });

  it("updates market state in place and queues asynchronous exports", async () => {
    const user = userEvent.setup();
    const job = { export_id: "11111111-1111-4111-8111-111111111111", status: "QUEUED" as const, format: "CSV" as const, query_fingerprint: marketPageModel.queryFingerprint, created_at: "2026-10-19T00:00:00Z", expires_at: "2026-10-20T00:00:00Z" };
    marketApi.loadDashboard.mockImplementation(async (params: URLSearchParams) => ({
      ...marketPageModel,
      query: {
        ...marketPageModel.query,
        ...(params.get("price_gte") ? { price_gte: "250000.00" } : {}),
        sort: params.get("sort") ?? marketPageModel.query.sort,
        ...(params.get("cursor") ? { cursor: params.get("cursor")! } : {}),
      },
    }));
    marketApi.createExport.mockResolvedValue({ job, retryAfterSeconds: 10 });
    marketApi.getExport.mockResolvedValue({ job: { ...job, status: "SUCCEEDED" }, retryAfterSeconds: 2 });
    render(<MotionProvider><MarketDashboard initialModel={{ ...marketPageModel, query: { ...marketPageModel.query, cursor: "current-cursor" } }} /></MotionProvider>);

    await user.click(screen.getByText(/Market filters/));
    await user.type(screen.getByLabelText("Actual price minimum"), "250000");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => expect(marketApi.loadDashboard).toHaveBeenCalled());
    expect((marketApi.loadDashboard.mock.calls[0]?.[0] as URLSearchParams).toString()).toContain("price_gte=250000");
    await user.click(screen.getByRole("button", { name: /Actual price/ }));
    await waitFor(() => expect((marketApi.loadDashboard.mock.calls.at(-1)?.[0] as URLSearchParams).get("sort")).toBe("price:asc"));
    await user.click(screen.getByRole("button", { name: "Next results" }));
    await waitFor(() => expect((marketApi.loadDashboard.mock.calls.at(-1)?.[0] as URLSearchParams).get("cursor")).toBe("opaque-next"));
    await user.click(screen.getByRole("button", { name: "Previous results" }));
    await waitFor(() => expect((marketApi.loadDashboard.mock.calls.at(-1)?.[0] as URLSearchParams).has("cursor")).toBe(false));

    await user.click(screen.getByRole("button", { name: "Export CSV" }));
    await waitFor(() => expect(marketApi.createExport).toHaveBeenCalledOnce());
    expect(screen.getByText(/CSV · queued/)).toBeInTheDocument();
    expect(screen.getByText("CSV export queued.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(marketApi.cancelExport).toHaveBeenCalledWith(job.export_id);
  });
});
