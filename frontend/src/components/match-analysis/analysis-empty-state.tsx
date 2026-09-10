type Props = {
  reason: string | null;
};

export function AnalysisEmptyState({ reason }: Props) {
  return (
    <section className="analysis-empty" aria-labelledby="empty-analysis-title">
      <p className="eyebrow">Available evidence</p>
      <h2 id="empty-analysis-title">No supported match transitions</h2>
      <p>
        {reason ??
          "The available timeline does not support a material transition for this match."}
      </p>
    </section>
  );
}
