import { HashRouter, Route, Routes } from "react-router-dom";
import { AppLayout } from "./AppLayout";
import { CatalogEditor } from "./CatalogEditor";
import { CatalogList } from "./CatalogList";
import { DocsRoutes } from "./Docs";
import { JobBuilder } from "./JobBuilder";
import { Health } from "./Health";
import { JobDetail } from "./JobDetail";
import { Logs } from "./Logs";
import { ManagementHome } from "./ManagementHome";
import { McpPage } from "./Mcp";
import { Settings } from "./Settings";

export function App() {
  return (
    <HashRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<ManagementHome />} />
          <Route path="/job/builder" element={<JobBuilder />} />
          <Route path="/job/:jobId" element={<JobDetail />} />
          <Route path="/catalog" element={<CatalogList />} />
          <Route path="/catalog/items/:id" element={<CatalogEditor />} />
          <Route path="/health" element={<Health />} />
          <Route path="/logs" element={<Logs />} />
          <Route path="/docs/*" element={<DocsRoutes />} />
          <Route path="/mcp" element={<McpPage />} />
          <Route path="/settings" element={<Settings />} />
        </Route>
      </Routes>
    </HashRouter>
  );
}
