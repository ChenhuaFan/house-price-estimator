import { createServer } from "node:http";
import { randomUUID } from "node:crypto";

const dataRevision = 1;
const queryHash = `sha256:${"b".repeat(64)}`;
const correlation = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

const properties = Array.from({ length: 50 }, (_, index) => {
  const id = index + 1;
  return {
    source_id: id,
    square_footage: 780 + id * 46,
    bedrooms: 1 + (id % 5),
    bathrooms: (1 + (id % 5) * 0.5).toFixed(1),
    year_built: 1970 + (id % 50),
    lot_size: 1800 + id * 190,
    distance_to_city_center: (1 + (id % 18) * 0.65).toFixed(3),
    school_rating: (4 + (id % 13) * 0.45).toFixed(3),
    price: (155000 + id * 11750).toFixed(2),
  };
});

const propertyInput = {
  square_footage: 1800,
  bedrooms: 3,
  bathrooms: 2.5,
  year_built: 2005,
  lot_size: 6500,
  distance_to_city_center: 4.125,
  school_rating: 8.5,
};

let estimates = [
  { estimate_id: "11111111-1111-4111-8111-111111111111", request_id: "21111111-1111-4111-8111-111111111111", created_at: "2026-10-19T00:00:00Z", inputs: propertyInput, predicted_price: "412845.00", currency: "USD", model_version: "housing-linear-demo-v2" },
  { estimate_id: "33333333-3333-4333-8333-333333333333", request_id: "43333333-3333-4333-8333-333333333333", created_at: "2026-10-18T00:00:00Z", inputs: { ...propertyInput, square_footage: 1450, bedrooms: 2 }, predicted_price: "351920.00", currency: "USD", model_version: "housing-linear-demo-v2" },
];
const exportJobs = new Map();

function json(response, status, body, headers = {}) {
  response.writeHead(status, { "content-type": "application/json", "x-correlation-id": correlation, ...headers });
  response.end(JSON.stringify(body));
}

async function body(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : null;
}

function applyFilters(items, source) {
  const ranges = [
    ["price", "price_gte", "price_lte"], ["square_footage", "square_footage_gte", "square_footage_lte"],
    ["bedrooms", "bedrooms_gte", "bedrooms_lte"], ["bathrooms", "bathrooms_gte", "bathrooms_lte"],
    ["year_built", "year_built_gte", "year_built_lte"], ["lot_size", "lot_size_gte", "lot_size_lte"],
    ["distance_to_city_center", "distance_to_city_center_gte", "distance_to_city_center_lte"],
    ["school_rating", "school_rating_gte", "school_rating_lte"],
  ];
  return items.filter((item) => ranges.every(([field, minimum, maximum]) => {
    const range = source[field];
    const gte = source[minimum] ?? range?.gte;
    const lte = source[maximum] ?? range?.lte;
    const value = Number(item[field]);
    return (gte === undefined || value >= Number(gte)) && (lte === undefined || value <= Number(lte));
  }));
}

function statistic(method, field, rows) {
  if (method === "COUNT") return String(rows.length);
  if (rows.length === 0) return undefined;
  const values = rows.map((item) => Number(item[field]));
  if (method === "AVG") return (values.reduce((sum, value) => sum + value, 0) / values.length).toFixed(2);
  if (method === "MIN") return Math.min(...values).toFixed(2);
  if (method === "MAX") return Math.max(...values).toFixed(2);
  if (method === "STDDEV_POP") {
    const average = values.reduce((sum, value) => sum + value, 0) / values.length;
    return Math.sqrt(values.reduce((sum, value) => sum + (value - average) ** 2, 0) / values.length).toFixed(2);
  }
  return "0";
}

createServer(async (request, response) => {
  const url = new URL(request.url, "http://127.0.0.1:18081");
  if (url.pathname === "/api/v1/estimates" && request.method === "GET") return json(response, 200, { items: estimates, next_cursor: null });
  if (url.pathname === "/api/v1/estimates" && request.method === "POST") {
    const input = await body(request);
    const { request_id: requestId, ...features } = input;
    const created = { estimate_id: randomUUID(), request_id: requestId, created_at: new Date().toISOString(), inputs: features, predicted_price: "427630.00", currency: "USD", model_version: "housing-linear-demo-v2" };
    estimates = [created, ...estimates];
    return json(response, 201, created);
  }
  if (url.pathname === "/api/v1/estimates" && request.method === "DELETE") { estimates = []; response.writeHead(204); return response.end(); }
  if (url.pathname.startsWith("/api/v1/estimates/") && request.method === "DELETE") { estimates = estimates.filter((item) => !url.pathname.endsWith(item.estimate_id)); response.writeHead(204); return response.end(); }
  return json(response, 404, { error: { code: "NOT_FOUND", message: "Not found" }, correlation_id: correlation });
}).listen(18081, "127.0.0.1");

createServer(async (request, response) => {
  const url = new URL(request.url, "http://127.0.0.1:18080");
  if (url.pathname === "/api/v2/market/properties" && request.method === "GET") {
    let rows = applyFilters(properties, Object.fromEntries(url.searchParams));
    const [sortField = "source_id", direction = "asc"] = (url.searchParams.get("sort") ?? "source_id:asc").split(":");
    rows.sort((left, right) => (Number(left[sortField]) - Number(right[sortField])) * (direction === "desc" ? -1 : 1));
    const start = url.searchParams.get("cursor") ? Number(url.searchParams.get("cursor").replace("cursor-", "")) : 0;
    const limit = Number(url.searchParams.get("limit") ?? 20);
    await new Promise((resolve) => setTimeout(resolve, url.searchParams.has("price_gte") ? 350 : 30));
    return json(response, 200, {
      data_revision: dataRevision, query_fingerprint: queryHash,
      items: rows.slice(start, start + limit), page: { limit, has_more: start + limit < rows.length, ...(start + limit < rows.length ? { next_cursor: `cursor-${start + limit}` } : {}) },
    });
  }
  if (url.pathname === "/api/v2/market/statistics/query" && request.method === "POST") {
    const query = await body(request);
    const rows = applyFilters(properties, query.scope.filters ?? {});
    let groups = [{ key: {}, rows }];
    if (query.group_by?.field) {
      const grouped = new Map();
      for (const row of rows) {
        const raw = Number(row[query.group_by.field]);
        const width = Number(query.group_by.bucket?.width ?? 0);
        const key = width ? Math.floor(raw / width) * width : raw;
        if (!grouped.has(key)) grouped.set(key, []);
        grouped.get(key).push(row);
      }
      groups = [...grouped.entries()].sort(([a], [b]) => a - b).map(([key, values]) => ({ key: widthKey(query.group_by.field, key, Number(query.group_by.bucket?.width ?? 0)), rows: values }));
    }
    return json(response, 200, { data_revision: dataRevision, query_fingerprint: queryHash, groups: groups.map((group) => ({ key: group.key, values: query.metrics.map((metric) => ({ metric_id: metric.id, ...(statistic(metric.method, metric.field, group.rows) !== undefined ? { value: statistic(metric.method, metric.field, group.rows) } : {}) })) })) });
  }
  if (url.pathname === "/api/v2/market/what-if" && request.method === "POST") {
    const input = await body(request);
    return json(response, 200, { inputs: input, prediction: { predicted_price: "427630.00", currency: "USD", model_version: "housing-linear-demo-v2", warnings: [] } });
  }
  if (url.pathname === "/api/v2/market/exports" && request.method === "POST") {
    const input = await body(request);
    const id = randomUUID();
    const job = { export_id: id, status: "QUEUED", format: input.format, data_revision: dataRevision, query_fingerprint: queryHash, created_at: new Date().toISOString(), expires_at: new Date(Date.now() + 86400000).toISOString() };
    exportJobs.set(id, job);
    return json(response, 202, job, { location: `/api/v2/market/exports/${id}`, "retry-after": "1" });
  }
  const exportMatch = url.pathname.match(/^\/api\/v2\/market\/exports\/([0-9a-f-]+)$/i);
  if (exportMatch && request.method === "GET") {
    const job = exportJobs.get(exportMatch[1]);
    if (!job) return json(response, 404, { error: { code: "NOT_FOUND", message: "Export not found" }, correlation_id: correlation });
    const complete = { ...job, status: "SUCCEEDED", completed_at: new Date().toISOString(), row_count: 50, detail_row_count: job.format === "PDF" ? 50 : 0, details_truncated: false, content_length: job.format === "PDF" ? 20 : 36, content_sha256: "c".repeat(64), download_url: `${url.pathname}/content` };
    exportJobs.set(job.export_id, complete);
    return json(response, 200, complete);
  }
  if (exportMatch && request.method === "DELETE") { const job = exportJobs.get(exportMatch[1]); if (job) exportJobs.set(job.export_id, { ...job, status: "CANCELLED" }); response.writeHead(202); return response.end(); }
  const contentMatch = url.pathname.match(/^\/api\/v2\/market\/exports\/([0-9a-f-]+)\/content$/i);
  if (contentMatch && request.method === "GET") {
    const job = exportJobs.get(contentMatch[1]);
    const content = job?.format === "PDF" ? "%PDF-1.4\nmock report" : "source_id,price\n1,166750.00\n";
    response.writeHead(200, { "content-type": job?.format === "PDF" ? "application/pdf" : "text/csv", "content-disposition": `attachment; filename=market.${job?.format === "PDF" ? "pdf" : "csv"}`, "content-length": Buffer.byteLength(content), "content-digest": "sha-256=:mock:", etag: '"mock"' });
    return response.end(content);
  }
  return json(response, 404, { error: { code: "NOT_FOUND", message: "Not found" }, correlation_id: correlation });
}).listen(18080, "127.0.0.1");

function widthKey(field, key, width) {
  return width ? { field, gte: String(key), lt: String(key + width), label: `${key}–${key + width}` } : { field, exact_value: String(key) };
}

createServer((_request, response) => { response.writeHead(200); response.end("ok"); }).listen(18099, "127.0.0.1", () => {
  process.stdout.write("Mock estimator and market backends ready\n");
});

function shutdown() { process.exit(0); }
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
