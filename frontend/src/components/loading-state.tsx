export function LoadingStatus({ title, description }: { title: string; description: string }) {
  return <div className="entry-loading-status" role="status">
    <span className="entry-spinner" aria-hidden="true" />
    <div><strong>{title}</strong><p>{description}</p></div>
  </div>;
}

export function HistorySkeleton() {
  return <div className="entry-history-skeleton" aria-hidden="true">
    {[0, 1, 2].map(row => <div className="entry-skeleton-row" key={row}>
      <span className="entry-skeleton entry-skeleton--portrait" />
      <div><span className="entry-skeleton" /><span className="entry-skeleton entry-skeleton--short" /></div>
      <span className="entry-skeleton entry-skeleton--stat" />
    </div>)}
  </div>;
}
