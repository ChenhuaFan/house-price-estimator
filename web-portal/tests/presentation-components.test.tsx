import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppHeader } from "@/components/app-shell/app-header";
import { PropertyFields } from "@/components/ui/property-fields";
import { StatusMessage } from "@/components/ui/status-message";
import { Comparison } from "@/features/estimator/comparison";
import { EstimateResult } from "@/features/estimator/estimate-result";
import { HistoryTable } from "@/features/estimator/history-table";
import { MarketCharts } from "@/features/market/market-charts";
import { MarketTable } from "@/features/market/market-table";
import { formatMoney, formatNumber, formatTimestamp } from "@/lib/format";
import { emptyPropertyDraft } from "@/lib/validation/property";
import { estimate, marketAnalysis } from "@/tests/fixtures";

const navigation = vi.hoisted(() => ({
  pathname: "/estimator",
  query: new URLSearchParams("priceMin=100"),
  push: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
  useSearchParams: () => navigation.query,
  useRouter: () => ({ push: navigation.push }),
}));

describe("shared and presentation components", () => {
  beforeEach(() => {
    navigation.pathname = "/estimator";
    navigation.query = new URLSearchParams("priceMin=100");
    navigation.push.mockReset();
  });

  it("renders the shared portal brand and marks the active application", () => {
    render(<AppHeader />);
    expect(screen.getByRole("link", { name: "Housing Intelligence Portal home" }))
      .toHaveAttribute("href", "/estimator");
    expect(screen.getByRole("link", { name: "Property estimator" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Market analysis" })).not.toHaveAttribute("aria-current");
  });

  it("renders accessible field help, errors, changes, and blur callbacks", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onBlur = vi.fn();
    render(<PropertyFields
      draft={emptyPropertyDraft}
      errors={{ bathrooms: "Use 0.5 increments." }}
      onChange={onChange}
      onBlur={onBlur}
      idPrefix="test"
    />);

    const bathrooms = screen.getByLabelText(/Bathrooms/);
    expect(bathrooms).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("alert")).toHaveTextContent("Use 0.5 increments.");
    await user.type(bathrooms, "2.5");
    await user.tab();
    expect(onChange).toHaveBeenLastCalledWith("bathrooms", expect.any(String));
    expect(onBlur).toHaveBeenCalledWith("bathrooms");
  });

  it("assigns correct live-region semantics to status messages", () => {
    const { rerender } = render(<StatusMessage title="Information">Details</StatusMessage>);
    expect(screen.getByText("Information").parentElement).not.toHaveAttribute("role");
    rerender(<StatusMessage tone="warning" title="Advisory" live>Check it</StatusMessage>);
    expect(screen.getByRole("status")).toHaveAttribute("aria-live", "polite");
    rerender(<StatusMessage tone="error" title="Failed">Try again</StatusMessage>);
    expect(screen.getByRole("alert")).toHaveTextContent("Try again");
  });

  it("formats exact UI values and UTC timestamps", () => {
    expect(formatMoney("1234.50")).toBe("$1,234.50");
    expect(formatNumber("1234.567")).toBe("1,234.57");
    expect(formatTimestamp("2026-10-18T10:00:00Z")).toContain("UTC");
  });

  it("shows both the empty and successful estimate result with advisories", () => {
    const { rerender } = render(<EstimateResult estimate={null} />);
    expect(screen.getByRole("heading", { name: "Ready when you are" })).toBeInTheDocument();
    const result = estimate();
    result.warnings = [{
      field: "school_rating",
      submitted_value: "11",
      advisory_min: "1",
      advisory_max: "10",
      reason: "Outside advisory range.",
    }];
    rerender(<EstimateResult estimate={result} />);
    expect(screen.getAllByText("$237,354.67").length).toBeGreaterThan(1);
    expect(screen.getByRole("img")).toHaveAccessibleName(/predicted price \$237,354.67/);
    expect(screen.getByRole("status")).toHaveTextContent("Outside advisory range.");
  });

  it("shows comparison only after two properties are selected", () => {
    const first = estimate();
    const second = estimate("33333333-3333-4333-8333-333333333333");
    const { rerender, container } = render(<Comparison estimates={[first]} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<Comparison estimates={[first, second]} />);
    expect(screen.getByRole("heading", { name: "Properties side by side" })).toBeInTheDocument();
    expect(screen.getAllByText("$237,354.67")).toHaveLength(2);
    expect(screen.getByRole("rowheader", { name: /Interior floor area/ })).toBeInTheDocument();
  });

  it("supports history comparison, confirmation-protected deletion, clear, and pagination", async () => {
    const user = userEvent.setup();
    const first = estimate();
    const second = estimate("33333333-3333-4333-8333-333333333333");
    const onToggle = vi.fn();
    const onView = vi.fn();
    const onDelete = vi.fn().mockResolvedValue(undefined);
    const onClear = vi.fn().mockResolvedValue(undefined);
    const onLoadMore = vi.fn().mockResolvedValue(undefined);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const { rerender } = render(<HistoryTable
      estimates={[]}
      selectedIds={new Set()}
      nextCursor={null}
      busy={false}
      onToggle={onToggle}
      onView={onView}
      onDelete={onDelete}
      onClear={onClear}
      onLoadMore={onLoadMore}
    />);
    expect(screen.getByText("No estimates yet")).toBeInTheDocument();

    rerender(<HistoryTable
      estimates={[first, second]}
      selectedIds={new Set([first.estimate_id])}
      nextCursor="next"
      busy={false}
      onToggle={onToggle}
      onView={onView}
      onDelete={onDelete}
      onClear={onClear}
      onLoadMore={onLoadMore}
    />);
    await user.click(screen.getByRole("checkbox", { name: `Compare estimate ${second.estimate_id}` }));
    await user.click(screen.getAllByRole("button", { name: "View" })[0]!);
    await user.click(screen.getAllByRole("button", { name: "Delete" })[0]!);
    await user.click(screen.getByRole("button", { name: "Clear history" }));
    await user.click(screen.getByRole("button", { name: "Load more history" }));
    expect(onToggle).toHaveBeenCalledWith(second.estimate_id);
    expect(onView).toHaveBeenCalledWith(first.estimate_id);
    expect(onDelete).toHaveBeenCalledWith(first.estimate_id);
    expect(onClear).toHaveBeenCalledOnce();
    expect(onLoadMore).toHaveBeenCalledOnce();
  });

  it("disables a fifth comparison and respects cancelled destructive actions", async () => {
    const user = userEvent.setup();
    const record = estimate();
    const selected = new Set(["a", "b", "c", "d"]);
    const onDelete = vi.fn();
    const onClear = vi.fn();
    vi.spyOn(window, "confirm").mockReturnValue(false);
    render(<HistoryTable
      estimates={[record]}
      selectedIds={selected}
      nextCursor={null}
      busy={false}
      onToggle={vi.fn()}
      onView={vi.fn()}
      onDelete={onDelete}
      onClear={onClear}
      onLoadMore={vi.fn()}
    />);
    expect(screen.getByRole("checkbox")).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Clear history" }));
    expect(onDelete).not.toHaveBeenCalled();
    expect(onClear).not.toHaveBeenCalled();
  });

  it("renders chart alternatives for populated and empty data", () => {
    const { rerender } = render(<MarketCharts
      priceByBedrooms={marketAnalysis.charts.average_price_by_bedrooms}
      countBySchoolBand={marketAnalysis.charts.property_count_by_school_band}
    />);
    expect(screen.getByRole("img", { name: /Average price by bedrooms/ })).toHaveAccessibleName(/\$250,000.00/);
    expect(screen.getAllByText(/8\+: 2/).length).toBeGreaterThan(0);
    rerender(<MarketCharts priceByBedrooms={[]} countBySchoolBand={[]} />);
    expect(screen.getAllByText("No matching data to chart.")).toHaveLength(2);
  });

  it("renders market records and drives stable sorting and pagination", async () => {
    const user = userEvent.setup();
    const paged = { ...marketAnalysis, matchingCount: 45, size: 20 };
    render(<MarketTable analysis={paged} sort="source_id" direction="asc" />);
    expect(screen.getByText("$250,000.00")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: /ID/ })).toHaveAttribute("aria-sort", "ascending");

    await user.click(screen.getByRole("button", { name: "Actual price" }));
    expect(navigation.push).toHaveBeenCalledWith(expect.stringContaining("sort=price"));
    expect(navigation.push).toHaveBeenCalledWith(expect.stringContaining("direction=asc"));
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(navigation.push).toHaveBeenLastCalledWith(expect.stringContaining("page=1"));
  });

  it("shows the market empty state and reverses an active ascending sort", async () => {
    const user = userEvent.setup();
    render(<MarketTable
      analysis={{ ...marketAnalysis, records: [], matchingCount: 0 }}
      sort="price"
      direction="asc"
    />);
    expect(screen.getByText(/No properties match this segment/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous page" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: /Actual price/ }));
    expect(navigation.push).toHaveBeenCalledWith(expect.stringContaining("direction=desc"));
  });
});
