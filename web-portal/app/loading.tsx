export default function Loading(): React.ReactElement {
  return (
    <div aria-busy="true" aria-live="polite" className="space-y-6">
      <span className="sr-only">Loading page</span>
      <div className="skeleton h-28 rounded-3xl" />
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="skeleton h-72 rounded-2xl lg:col-span-2" />
        <div className="skeleton h-72 rounded-2xl" />
      </div>
    </div>
  );
}
