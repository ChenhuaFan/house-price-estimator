/** Locale-stable display formatters shared by both frontend applications. */

const currencyFormatter = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const numberFormatter = new Intl.NumberFormat("en-US", {
  maximumFractionDigits: 2,
});

/** Formats an exact API decimal or number as US dollars. */
export function formatMoney(value: string | number): string {
  return currencyFormatter.format(Number(value));
}

/** Formats a numeric display value with at most two fractional digits. */
export function formatNumber(value: string | number): string {
  return numberFormatter.format(Number(value));
}

/** Formats an API timestamp explicitly in UTC. */
export function formatTimestamp(value: string): string {
  return new Intl.DateTimeFormat("en-US", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC",
  }).format(new Date(value)) + " UTC";
}
