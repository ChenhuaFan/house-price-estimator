# Housing Intelligence Portal — Design System

**Reference direction:** the approved market-analysis dashboard supplied on 2026-10-18.
**Product character:** trustworthy, analytical, compact, and operational rather than promotional.

## Visual foundation

| Role | Value | Usage |
| --- | --- | --- |
| Shell | `#142333` | Fixed desktop navigation |
| Shell deep | `#0F1C29` | Sidebar depth and footer block |
| Primary | `#D93036` | Primary actions, active navigation, important values; WCAG AA with white |
| Canvas | `#F4F7FA` | Application background |
| Surface | `#FFFFFF` | Cards, forms, tables, popovers |
| Ink | `#0D1B33` | Headings and primary text |
| Muted ink | `#637083` | Supporting copy and metadata |
| Border | `#DDE4EC` | Dividers, fields, card outlines |
| Chart blue | `#1F6FCA` | Price trend series and accessible text links |
| Positive | `#24845B` | Minimum/healthy states |
| Warning | `#D77A19` | Average/attention states |
| Danger | `#D62F38` | Errors and destructive actions |

Use red deliberately. It identifies the active route, the most important action, and the predicted value; it is not a decorative fill for every card. Charts keep their own semantic blue/green/orange palette.

## Typography and density

- Font stack: `Inter`, `ui-sans-serif`, system sans-serif. Numeric values use tabular figures.
- Page heading: 30–36 px, 800 weight, compact line height.
- Card heading: 16–18 px, 750–800 weight.
- Body: 14 px. Metadata and dense table headers: 11–12 px.
- Base spacing unit: 4 px. Primary rhythm: 8 / 12 / 16 / 24 / 32 px.
- Controls are at least 44 px high; compactness comes from grouping and typography, never undersized hit targets.

## Layout

- Desktop: fixed 224 px dark sidebar; content occupies the remaining viewport.
- Market page: main analytics column plus a sticky 288 px What-if rail at `xl` widths.
- Mobile: sidebar becomes a compact horizontal header/nav; every dashboard section stacks naturally.
- Maximum information density is preferred, but every panel keeps a 12–16 px inner gutter.
- Horizontal scrolling is allowed inside wide tables only, never at document level.

## Components

### Navigation

- White brand lockup on dark navy.
- Active item uses a red left rail and a subtle red-tinted background.
- Icons come from Lucide and always have a visible text label.

### Buttons

- Primary: red fill, white 700-weight label, 8 px radius.
- Secondary: white fill, slate border, ink label.
- Destructive operations use the danger token and explicit copy.
- Hover changes color/shadow without layout movement. Focus uses a high-contrast 2 px ring.

### Panels and metric cards

- White surface, 1 px cool-gray border, 10–12 px radius, subtle shadow.
- Metric cards combine a tinted circular icon, large numeric value, and short label.
- Cards do not lift or scale on hover unless the entire card is interactive.

### Filters

- One compact filter bar containing seven grouped popovers and Apply/Reset actions.
- Each summary exposes whether values are active. Min/max validation focuses the first invalid field.
- URL parameters are the shareable source of truth after Apply.

### Charts

- Chart.js renders a blue price line/area chart and a horizontal school-rating count chart.
- Tooltips expose exact values; visible text/data fallbacks remain available to assistive technology.
- Animation is disabled for predictable analytical reading and reduced-motion compliance.

### Tables

- TanStack Table supplies the headless table model; the backend owns sort and pagination.
- Headers expose `aria-sort`; rows use tabular numbers and a restrained hover fill.
- Pagination reflects the backend page, page size, and matching count.

### Forms and status

- Property inputs preserve the canonical seven-field contract and show units inline.
- Errors sit adjacent to their field and are summarized in a semantic alert.
- Loading, empty, error, and success states must be designed, not inferred from disabled content.

## Rendering and component boundaries

- The Next.js shared layout, navigation, page title, and explanatory copy are Server Components.
- Business data (market analysis and estimate history) is requested from Client Component hooks through same-origin BFF routes after hydration.
- Smart containers own fetching, URL state, mutations, and exports.
- Dumb views receive serializable state and callbacks; field groups, charts, and tables remain independently testable.
- CSV/PDF requests reuse the current filter query and resolved dataset version so exports match the screen.

## Motion and accessibility

- Transitions: 150–200 ms, color/opacity/shadow only.
- Honor `prefers-reduced-motion`; never depend on motion to communicate state.
- WCAG AA text contrast, visible keyboard focus, semantic landmarks, and a skip link are mandatory.
- All interactive controls use SVG icons rather than emoji; icons that repeat text are hidden from assistive technology.

## Responsive checkpoints

- 390 px: no document-level overflow; filter groups and What-if form stack; table owns its scroll region.
- 768 px: two-column KPI/form layouts where useful.
- 1024 px: desktop sidebar active; analytics remains single-column until enough width exists.
- 1440 px+: analytics and sticky What-if rail display side by side; five KPI cards share one row.

## Pre-delivery checklist

- [ ] Visual language matches the approved dark-sidebar/red-accent reference.
- [ ] Desktop and 390 px mobile layouts have no page-level horizontal overflow.
- [ ] Keyboard focus, skip link, labels, live regions, and chart alternatives work.
- [ ] Filters, sort, pagination, and exports remain URL/data consistent.
- [ ] Loading, empty, error, retry, and reduced-motion states are verified.
- [ ] Client business requests do not run from Server Components.
