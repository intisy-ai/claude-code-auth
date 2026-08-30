import type { HandlerCtx } from "@intisy-ai/basekit/ir";

/** The two shapes a handler context's log is observed to have. */
type ContextLog = HandlerCtx["log"] | ((message: string) => void);

/**
 * A one-argument diagnostic sink over the handler context's logger.
 *
 * @remarks
 * The declared `HandlerCtx.log` is a `Logger` object with `debug`/`info`/`warn`/`error`, and the
 * Java router builds one. The TypeScript front-door does not: `java-route.ts`'s `contextOf` puts a
 * plain `(message: string) => void` there. Both shapes therefore reach a provider, and calling the
 * wrong one throws on a failure path, which is where a second failure is least affordable.
 *
 * The disagreement is a defect in the layer that declares the type, not something to be settled
 * here; until it is settled, this accepts whichever arrives.
 *
 * @param ctx - the handler context, which a caller may not have
 * @returns a function that writes one diagnostic line, and does nothing when there is nowhere to
 */
export function diagnostic(ctx?: { log?: ContextLog }): (message: string) => void {
  const log = ctx?.log;
  if (typeof log === "function") return log;
  if (log && typeof log.warn === "function") return (message: string) => { log.warn(message); };
  return () => {};
}
