import {
  BrowserRouter,
  Routes,
  Route,
  Navigate,
  Link,
  useParams,
} from "react-router-dom";
import { Shell } from "./layout/Shell";
import { Fleet } from "./pages/Fleet";
import { VehicleDetail } from "./pages/VehicleDetail";
import { Releases, CreateRelease, ReleaseDetail } from "./pages/Releases";
import { Rollouts } from "./pages/Rollouts";
import { NewRollout } from "./pages/NewRollout";
import { RolloutDetail } from "./pages/RolloutDetail";
import { DeploymentDetail } from "./pages/DeploymentDetail";
function VehicleRoute() {
  const { id } = useParams();
  return <VehicleDetail key={id} />;
}
function ReleaseRoute() {
  const { id } = useParams();
  return <ReleaseDetail key={id} />;
}
function RolloutRoute() {
  const { id } = useParams();
  return <RolloutDetail key={id} />;
}
function DeploymentRoute() {
  const { id } = useParams();
  return <DeploymentDetail key={id} />;
}
export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Shell />}>
          <Route path="/" element={<Navigate to="/fleet" replace />} />
          <Route path="/fleet" element={<Fleet />} />
          <Route path="/vehicles/:id" element={<VehicleRoute />} />
          <Route path="/releases" element={<Releases />} />
          <Route path="/releases/new" element={<CreateRelease />} />
          <Route path="/releases/:id" element={<ReleaseRoute />} />
          <Route path="/rollouts" element={<Rollouts />} />
          <Route path="/rollouts/new" element={<NewRollout />} />
          <Route path="/rollouts/:id" element={<RolloutRoute />} />
          <Route path="/deployments/:id" element={<DeploymentRoute />} />
          <Route
            path="*"
            element={
              <div className="empty">
                <h1>Page not found</h1>
                <Link to="/fleet">Return to fleet</Link>
              </div>
            }
          />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
