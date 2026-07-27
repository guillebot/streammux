import { HashRouter, Route, Routes } from "react-router-dom";
import { AppLayout } from "./AppLayout";
import { CatalogEditor } from "./CatalogEditor";
import { CatalogList } from "./CatalogList";
import { DocsRoutes } from "./Docs";
import { JobBuilder } from "./JobBuilder";
import { Health } from "./Health";
import { JobDetail } from "./JobDetail";
import { ManagementHome } from "./ManagementHome";
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
          <Route path="/docs/*" element={<DocsRoutes />} />
          <Route path="/settings" element={<Settings />} />
        </Route>
      </Routes>
    </HashRouter>
  );
}
