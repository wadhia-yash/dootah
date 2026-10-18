// Cloudflare Pages Function. public/_routes.json limits invocations to Cloud paths;
// anything else falls through to the static site.
import { isCloudPath, proxy, type EdgeEnv } from "../src/lib/edge";

interface Context {
  request: Request;
  env: EdgeEnv & { ASSETS: { fetch: (request: Request) => Promise<Response> } };
}

export const onRequest = ({ request, env }: Context) =>
  isCloudPath(new URL(request.url).pathname) ? proxy(request, env) : env.ASSETS.fetch(request);
