import { site } from "@/lib/site";
export function DootahMark() {
  return <svg viewBox="0 0 40 40" fill="none" aria-hidden="true"><path d="M27 7v25H16a10 10 0 1 1 0-20h5v6h-5a4 4 0 1 0 0 8h5V7h6Z" fill="currentColor" /><path d="m29 4 7 7h-5v8h-4V4h2Z" fill="currentColor" /></svg>;
}
export function Brand({ light = false }: { light?: boolean }) {
  return <a className={`brand ${light ? "brand-light" : ""}`} href={site()} aria-label="Dootah home"><span className="brand-mark"><DootahMark /></span>dootah</a>;
}
