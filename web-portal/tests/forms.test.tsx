import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EstimateForm } from "@/features/estimator/estimate-form";
import { PortalApiError } from "@/features/estimator/api";
import { FilterForm } from "@/features/market/filter-form";
import { WhatIfForm } from "@/features/market/what-if-form";
import { propertyInput, whatIfResponse } from "@/tests/fixtures";

const navigation = vi.hoisted(() => ({ push: vi.fn() }));
const marketApi = vi.hoisted(() => ({ runWhatIf: vi.fn() }));
const marketOverview = { count: "2", minimumPrice: "200000.00", maximumPrice: "300000.00", averagePrice: "250000.00", averageSquareFootage: "1800", priceStandardDeviation: "50000.00" };

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: navigation.push }) }));
vi.mock("@/features/market/api", () => ({
  runWhatIf: marketApi.runWhatIf,
}));

async function fillProperty(user: ReturnType<typeof userEvent.setup>, prefix = ""): Promise<void> {
  const values: Array<[RegExp, string]> = [
    [/Interior floor area/, "1800"],
    [/Bedrooms/, "3"],
    [/Bathrooms/, "2.5"],
    [/Year built/, "2005"],
    [/Lot size/, "6500"],
    [/Distance to city centre/, "4.125"],
    [/School rating/, "8.5"],
  ];
  for (const [label, value] of values) {
    const field = screen.getByLabelText(label, prefix ? { selector: `#${prefix}` } : undefined);
    await user.type(field, value);
  }
}

describe("estimator and market forms", () => {
  beforeEach(() => {
    navigation.push.mockReset();
    marketApi.runWhatIf.mockReset();
  });

  it("shows field-level estimator validation and focuses the first invalid field", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<EstimateForm busy={false} apiError={null} onSubmit={onSubmit} />);

    await user.click(screen.getByRole("button", { name: "Estimate value" }));

    expect(screen.getAllByRole("alert")).toHaveLength(7);
    expect(screen.getByLabelText(/Interior floor area/)).toHaveFocus();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("reuses an idempotency key only for a failed retry of identical inputs", async () => {
    const user = userEvent.setup();
    const firstId = "11111111-1111-4111-8111-111111111111";
    const secondId = "22222222-2222-4222-8222-222222222222";
    vi.spyOn(crypto, "randomUUID")
      .mockReturnValueOnce(firstId)
      .mockReturnValueOnce(secondId);
    const onSubmit = vi.fn()
      .mockRejectedValueOnce(new Error("temporary"))
      .mockResolvedValue(undefined);
    render(<EstimateForm busy={false} apiError={null} onSubmit={onSubmit} />);
    await fillProperty(user);

    await user.click(screen.getByRole("button", { name: "Estimate value" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    await user.click(screen.getByRole("button", { name: "Estimate value" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
    expect(onSubmit.mock.calls[0]?.[1]).toBe(firstId);
    expect(onSubmit.mock.calls[1]?.[1]).toBe(firstId);

    const bedrooms = screen.getByLabelText(/Bedrooms/);
    await user.clear(bedrooms);
    await user.type(bedrooms, "4");
    await user.click(screen.getByRole("button", { name: "Estimate value" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(3));
    expect(onSubmit.mock.calls[2]).toEqual([{ ...propertyInput, bedrooms: 4 }, secondId]);
  });

  it("renders busy and safe backend error feedback for estimator users", () => {
    render(<EstimateForm
      busy
      apiError={new PortalApiError("Prediction is unavailable.", "SERVICE_UNAVAILABLE", "reference")}
      onSubmit={vi.fn()}
    />);
    expect(screen.getByRole("alert")).toHaveTextContent("Prediction is unavailable.");
    expect(screen.getByRole("alert")).toHaveTextContent("Reference: reference");
    expect(screen.getByRole("button", { name: "Estimating…" })).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("calculating and saving");
  });

  it("validates market ranges before navigation", async () => {
    const user = userEvent.setup();
    render(<FilterForm values={{ price_gte: "300000", price_lte: "100000" }} onApply={vi.fn()} onReset={vi.fn()} />);

    await user.click(screen.getByText(/Market filters/));
    await user.click(screen.getByRole("button", { name: "Apply filters" }));

    expect(screen.getByRole("alert")).toHaveTextContent("Actual price: minimum cannot exceed maximum.");
    expect(screen.getByLabelText("Actual price minimum", { selector: "#filter-price_gte" })).toHaveFocus();
    expect(navigation.push).not.toHaveBeenCalled();
  });

  it("emits validated market filters and reset actions for the smart container", async () => {
    const user = userEvent.setup();
    const onApply = vi.fn();
    const onReset = vi.fn();
    const { rerender } = render(<FilterForm values={{
      price_gte: "100000",
    }} onApply={onApply} onReset={onReset} />);
    await user.click(screen.getByText(/Market filters/));
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    expect(onApply).toHaveBeenCalledWith(expect.objectContaining({ price_gte: "100000" }));

    rerender(<FilterForm values={{ price_gte: "100000" }} pending onApply={onApply} onReset={onReset} />);
    expect(screen.getByRole("button", { name: "Applying…" })).toBeDisabled();
    rerender(<FilterForm values={{ price_gte: "100000" }} onApply={onApply} onReset={onReset} />);
    await user.click(screen.getByRole("button", { name: "Reset filters" }));
    expect(onReset).toHaveBeenCalledOnce();
  });

  it("validates what-if inputs before calling the model", async () => {
    const user = userEvent.setup();
    render(<WhatIfForm marketOverview={marketOverview} onResult={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Run what-if" }));
    expect(screen.getAllByRole("alert")).toHaveLength(7);
    expect(screen.getByLabelText(/Interior floor area/)).toHaveFocus();
    expect(marketApi.runWhatIf).not.toHaveBeenCalled();
  });

  it("shows a what-if result and advisory without creating estimator history", async () => {
    const user = userEvent.setup();
    const advisory = structuredClone(whatIfResponse);
    advisory.prediction.warnings = [{
      field: "school_rating",
      submitted_value: "11",
      advisory_minimum: "1",
      advisory_maximum: "10",
      message: "Outside advisory range.",
    }];
    marketApi.runWhatIf.mockResolvedValue(advisory);
    const onResult = vi.fn();
    render(<WhatIfForm marketOverview={marketOverview} onResult={onResult} />);
    await fillProperty(user);

    await user.click(screen.getByRole("button", { name: "Run what-if" }));

    expect(await screen.findByText("$237,354.67")).toBeInTheDocument();
    expect(screen.getByText(/Advisory: school_rating/)).toHaveTextContent("Outside advisory range.");
    expect(marketApi.runWhatIf).toHaveBeenCalledWith(propertyInput);
    expect(onResult).toHaveBeenCalledWith(advisory);
  });

  it("shows stable what-if errors with a reference", async () => {
    const user = userEvent.setup();
    marketApi.runWhatIf.mockRejectedValue(new PortalApiError(
      "Prediction is temporarily unavailable.", "SERVICE_UNAVAILABLE", "reference",
    ));
    render(<WhatIfForm marketOverview={marketOverview} onResult={vi.fn()} />);
    await fillProperty(user);
    await user.click(screen.getByRole("button", { name: "Run what-if" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Prediction is temporarily unavailable.");
    expect(screen.getByRole("alert")).toHaveTextContent("Reference: reference");
  });
});
