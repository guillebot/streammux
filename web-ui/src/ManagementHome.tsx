import { Link } from "react-router-dom";
import { JobsList } from "./JobsList";

export function ManagementHome() {
  return (
    <div className="page">
      <header className="page-header page-header--left">
        <div className="btn-row" style={{ margin: 0 }}>
          <Link className="button-link primary" to="/job/builder">
            Job builder
          </Link>
          <Link className="button-link" to="/job/new">
            New job
          </Link>
        </div>
        <div>
          <h1 className="page-title">Job management</h1>
          <p className="page-subtitle muted">Live jobs from job-management-api (Kafka-backed read model).</p>
        </div>
      </header>
      <JobsList />
    </div>
  );
}
