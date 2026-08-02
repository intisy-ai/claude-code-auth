// Proves the injected OpenCode front-door is actually wired: with def.serveDirect set,
// core-auth's dispatchOpencodeFetch takes the in-process direct path instead of the
// no-front-door 503, so an offline OpenCode request (no proxy daemon running) is served
// for real through the injected serveDirect.
import { it, expect } from "vitest";
import { serveDirect } from "../../opencode-proxy/dist/index.js";

it("core-auth dispatches offline OpenCode through the injected serveDirect (no 503)", async () => {
  const def: any = {
    id: "claude-code",
    handleIr: async () => ({
      id: "m",
      model: "m",
      content: [{ kind: "text", text: "ok" }],
      stopReason: "end_turn",
      usage: { inputTokens: 1, outputTokens: 1 },
    }),
    serveDirect,
  };
  const { dispatchOpencodeFetch } = await import("../../core-auth/dist/opencode-fetch.js");
  const res = await dispatchOpencodeFetch(
    def,
    new Request("http://x/v1/messages", {
      method: "POST",
      body: JSON.stringify({ model: "claude-opus-4-1", max_tokens: 8, messages: [{ role: "user", content: "hi" }] }),
    }),
    {},
    { configDir: "/tmp", log() {} },
  );
  expect(res.status).not.toBe(503);
});
