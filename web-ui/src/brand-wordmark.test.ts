import { describe, expect, it } from "vitest";
import { BRAND_WORDMARK, validateBrandWordmark } from "./lib/brand-wordmark";

describe("brand wordmark", () => {
  it("validates streammux split", () => {
    expect(() => validateBrandWordmark()).not.toThrow();
    expect(BRAND_WORDMARK.full).toBe("streammux");
    expect(BRAND_WORDMARK.first + BRAND_WORDMARK.second).toBe(BRAND_WORDMARK.full);
  });
});
