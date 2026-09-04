import { BRAND_WORDMARK, validateBrandWordmark } from "../lib/brand-wordmark";

validateBrandWordmark();

export { BRAND_WORDMARK };

export function BrandWordmark({ className }: { className?: string }) {
  return (
    <span className={className} aria-label={BRAND_WORDMARK.full}>
      {BRAND_WORDMARK.first}
      <span className="text-accent">{BRAND_WORDMARK.second}</span>
    </span>
  );
}
