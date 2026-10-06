import { client } from './auth.mjs';

/** Backend-only helper. Call only after your backend authenticates/authorizes the app user.
 * Keep the scoped publisher token on the backend. Return only this one-use ticket to native code.
 * Do not expose this function as an unauthenticated endpoint or call it from Portable IR.
 */
export async function mintEnrollmentTicket(config, environmentId) {
  const call = await client(config);
  const response = await call('/v1/enrollment-tickets', 'POST', { environmentId });
  return { ticket: response.secret, expiresInSeconds: 600 };
}
