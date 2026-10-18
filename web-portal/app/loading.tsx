export default function Loading(): React.ReactElement {
  return (
    <div aria-busy="true" aria-live="polite" className="space-y-5">
      <span className="sr-only">Loading page</span>
      <div className="skeleton h-16 rounded-xl" />
      <div className="grid gap-4 lg:grid-cols-5">{Array.from({ length: 5 }, (_, index) => <div key={index} className="skeleton h-24 rounded-xl" />)}</div>
      <div className="grid gap-4 lg:grid-cols-2"><div className="skeleton h-72 rounded-xl" /><div className="skeleton h-72 rounded-xl" /></div>
    </div>
  );
}
