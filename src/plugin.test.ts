import { describe, expect, it, vi } from "vitest";
import { providerSupport } from "@intisy-ai/basekit/auth";

const handleIr = vi.fn(async () => ({ id: "answered" }));

vi.mock("./driver/index.js", () => ({
  driver: { id: "claude-code", label: "Claude Code", models: {}, handleIr, loginFlow: async () => ({ url: "", complete: async () => null }) },
  CLAUDE_SETTINGS_SCHEMA: [],
  RETRY_KEYS: [],
}));

// The host's own service, which is where the provider helpers come from now. A test supplies the
// real one, so what it exercises is what a loader hands over rather than a stand-in for it.
function contextSpy(services: Record<string, unknown> = { "provider-support": providerSupport() }) {
  const provided: Record<string, unknown> = {};
  return {
    provided,
    context: {
      provide: vi.fn((key: string | { id: string }, value: unknown) => { provided[typeof key === "string" ? key : key.id] = value; }),
      // The engine mints a typed key from an id alone, which is all the plugin needs from it here.
      capability: (id: string) => ({ id }),
      service: (id: string) => ({ id }),
      services: { get: (key: { id: string }) => services[key.id] },
      paths: { home: "/tmp/home" },
    },
  };
}

describe("the claude-code-auth api plugin", () => {
  it("provides exactly the capabilities its manifest declares", async () => {
    const plugin = (await import("./plugin.js")).default;
    const { context, provided } = contextSpy();
    await plugin.activate(context as never);
    expect(Object.keys(provided).sort()).toEqual(["provider", "settings"]);
  });

  it("names the driver's provider id and advertises one lane", async () => {
    const plugin = (await import("./plugin.js")).default;
    const { context, provided } = contextSpy();
    await plugin.activate(context as never);
    const capability = provided.provider as { id: string; providers: () => Promise<Array<{ id: string }>> };
    expect(capability.id).toBe("claude-code");
    expect((await capability.providers()).map((lane) => lane.id)).toEqual(["claude-code"]);
  });

  it("delegates a request to the driver rather than calling upstream itself", async () => {
    const plugin = (await import("./plugin.js")).default;
    const { context, provided } = contextSpy();
    await plugin.activate(context as never);
    const capability = provided.provider as { handleIr: (r: unknown, c: unknown) => Promise<unknown> };
    const ctx = { configDir: "/tmp/home", log: () => {}, model: "m", provider: "claude-code" };
    await expect(capability.handleIr({ model: "m" }, ctx)).resolves.toEqual({ id: "answered" });
    expect(handleIr).toHaveBeenCalledWith({ model: "m" }, ctx);
  });

  // A host that offers no provider support cannot run a provider at all, and naming the service is
  // the only way an operator learns which host is at fault.
  it("names the missing service rather than leaving the capability unprovided", async () => {
    const plugin = (await import("./plugin.js")).default;
    const { context } = contextSpy({});
    await expect(async () => plugin.activate(context as never)).rejects.toThrow(/provider-support/);
  });

  it("deactivates without throwing", async () => {
    const plugin = (await import("./plugin.js")).default;
    expect(plugin.deactivate()).toBeUndefined();
  });
});
