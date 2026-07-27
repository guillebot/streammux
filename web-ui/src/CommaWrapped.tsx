/** Renders a comma-separated string with a line break after each comma. */
export function CommaWrapped({
  value,
  className = "mono",
}: {
  value: string;
  className?: string;
}) {
  const text = value.trim();
  if (!text) {
    return <span className={className}>—</span>;
  }

  const lines = text
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part, index, parts) => (index < parts.length - 1 ? `${part},` : part));

  return (
    <div className={`comma-wrapped ${className}`.trim()}>
      {lines.map((line, index) => (
        <span key={index}>{line}</span>
      ))}
    </div>
  );
}
