import { BrowserRouter, Routes, Route, Navigate, Link } from 'react-router-dom';
import { Shell } from './layout/Shell';
import { Fleet } from './pages/Fleet';
import { VehicleDetail } from './pages/VehicleDetail';
import { Releases, CreateRelease, ReleaseDetail } from './pages/Releases';
export function App(){ return <BrowserRouter><Routes><Route element={<Shell/>}><Route path="/" element={<Navigate to="/fleet" replace/>}/><Route path="/fleet" element={<Fleet/>}/><Route path="/vehicles/:id" element={<VehicleDetail key={undefined}/>}/><Route path="/releases" element={<Releases/>}/><Route path="/releases/new" element={<CreateRelease/>}/><Route path="/releases/:id" element={<ReleaseDetail key={undefined}/>}/><Route path="*" element={<div className="empty"><h1>Page not found</h1><Link to="/fleet">Return to fleet</Link></div>}/></Route></Routes></BrowserRouter>; }
