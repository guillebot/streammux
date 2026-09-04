export const BRAND_WORDMARK = {
  full: "streammux",
  first: "stream",
  second: "mux",
} as const;

export function validateBrandWordmark(): void {
  const { full, first, second } = BRAND_WORDMARK;
  if (full !== `${first}${second}`) {
    throw new Error(`BRAND_WORDMARK.full must equal first+second (${full} !== ${first}${second})`);
  }
  if (!/^[a-z]+$/.test(full)) {
    throw new Error("BRAND_WORDMARK must be all lowercase letters");
  }
}
