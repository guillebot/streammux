import apiMd from "../../docs/api.md?raw";
import architectureMd from "../../docs/architecture.md?raw";
import deploymentMd from "../../docs/deployment.md?raw";
import jobTypesMd from "../../docs/job-types.md?raw";
import overviewMd from "../../docs/overview.md?raw";
import usageMd from "../../docs/usage.md?raw";
import webConsoleMd from "../../docs/web-console.md?raw";

export type DocPage = {
  slug: string;
  title: string;
  description: string;
  content: string;
};

/** Operator-facing guides bundled at build time from repo `docs/`. */
export const DOC_PAGES: DocPage[] = [
  {
    slug: "overview",
    title: "Overview",
    description: "What Streammux is, components, and data flow",
    content: overviewMd,
  },
  {
    slug: "architecture",
    title: "Architecture",
    description: "Control plane, Kafka topics, and lease model",
    content: architectureMd,
  },
  {
    slug: "job-types",
    title: "Job types",
    description: "ROUTE_APP, RANDOM_SAMPLER, and ALARMS_TO_ZTR configuration",
    content: jobTypesMd,
  },
  {
    slug: "api",
    title: "API reference",
    description: "100% API-managed control plane, OpenAPI, and curl examples",
    content: apiMd,
  },
  {
    slug: "web-console",
    title: "Web console",
    description: "Using the management UI, builder, and catalog",
    content: webConsoleMd,
  },
  {
    slug: "usage",
    title: "API usage",
    description: "REST endpoints, metadata, catalog API, and scripts",
    content: usageMd,
  },
  {
    slug: "deployment",
    title: "Deployment",
    description: "Images, Compose, environment variables, and CI",
    content: deploymentMd,
  },
];

export const DOC_PAGES_BY_SLUG = new Map(DOC_PAGES.map((page) => [page.slug, page]));

/** Map relative markdown links (e.g. job-types.md) to in-app doc routes. */
export function resolveDocHref(href: string): string | null {
  const trimmed = href.trim();
  if (!trimmed || trimmed.startsWith("#")) return null;
  if (/^https?:\/\//i.test(trimmed) || trimmed.startsWith("/")) return null;
  if (trimmed.includes("..")) return null;

  const withoutAnchor = trimmed.split("#")[0] ?? "";
  if (!withoutAnchor.endsWith(".md")) return null;

  const file = withoutAnchor.split("/").pop() ?? "";
  const slug = file.replace(/\.md$/i, "");
  if (!DOC_PAGES_BY_SLUG.has(slug)) return null;
  return `/docs/${slug}`;
}
