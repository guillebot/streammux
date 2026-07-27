import { Link, Navigate, Route, Routes, useParams } from "react-router-dom";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { DOC_PAGES, DOC_PAGES_BY_SLUG, resolveDocHref } from "./docsContent";

function DocMarkdown({ content }: { content: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        a({ href, children, ...props }) {
          if (href) {
            const docRoute = resolveDocHref(href);
            if (docRoute) {
              return (
                <Link to={docRoute} {...props}>
                  {children}
                </Link>
              );
            }
            if (href.startsWith("/") && !href.startsWith("//")) {
              return (
                <a href={href} {...props}>
                  {children}
                </a>
              );
            }
          }
          return (
            <a href={href} target="_blank" rel="noreferrer" {...props}>
              {children}
            </a>
          );
        },
      }}
    >
      {content}
    </ReactMarkdown>
  );
}

export function DocsIndex() {
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">Documentation</h1>
          <p className="page-subtitle muted">
            Operator and integrator guides for Streammux. Content is sourced from the repository{" "}
            <code>docs/</code> folder at build time.
          </p>
        </div>
      </header>

      <div className="docs-grid">
        {DOC_PAGES.map((page) => (
          <Link key={page.slug} className="docs-card" to={`/docs/${page.slug}`}>
            <h2 className="docs-card-title">{page.title}</h2>
            <p className="docs-card-desc muted">{page.description}</p>
          </Link>
        ))}
      </div>

      <section className="panel" style={{ marginTop: "1.25rem" }}>
        <h2>External references</h2>
        <ul className="docs-external-list">
          <li>
            <a href="/#/mcp">MCP console</a> — tokens, tool catalog, and connection snippets
          </li>
          <li>
            <a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">
              Swagger UI
            </a>{" "}
            — interactive OpenAPI docs for job-management-api
          </li>
          <li>
            <a href="/v3/api-docs" target="_blank" rel="noreferrer">
              OpenAPI JSON
            </a>{" "}
            — machine-readable API specification
          </li>
        </ul>
        <p className="muted" style={{ marginTop: "0.75rem", marginBottom: 0 }}>
          Swagger and OpenAPI JSON are served on the same host as the web UI (nginx proxy in Docker,
          Vite dev proxy locally). Traefik on OneLab routes those paths to job-management-api directly.
        </p>
      </section>
    </div>
  );
}

export function DocPage() {
  const { slug } = useParams<{ slug: string }>();
  const page = slug ? DOC_PAGES_BY_SLUG.get(slug) : undefined;

  if (!page) {
    return <Navigate to="/docs" replace />;
  }

  return (
    <div className="page">
      <div className="back-row">
        <Link to="/docs">← Documentation</Link>
      </div>

      <article className="docs-article markdown-body">
        <DocMarkdown content={page.content} />
      </article>

      <nav className="docs-pager" aria-label="Documentation pages">
        {DOC_PAGES.map((entry) => (
          <Link
            key={entry.slug}
            className={entry.slug === page.slug ? "docs-pager-link active" : "docs-pager-link"}
            to={`/docs/${entry.slug}`}
          >
            {entry.title}
          </Link>
        ))}
      </nav>
    </div>
  );
}

export function DocsRoutes() {
  return (
    <Routes>
      <Route index element={<DocsIndex />} />
      <Route path=":slug" element={<DocPage />} />
    </Routes>
  );
}
