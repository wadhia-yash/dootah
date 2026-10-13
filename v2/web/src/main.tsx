import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import Landing from "./Landing";
import Console from "./Console";
import { isConsolePath } from "./lib/site";
import "./index.css";
import "./expo.css";
const consolePage = isConsolePath(window.location.pathname);
if (consolePage) document.title = "Release console · Dootah";
createRoot(document.getElementById("root")!).render(
  <StrictMode>{consolePage ? <Console /> : <Landing />}</StrictMode>,
);
