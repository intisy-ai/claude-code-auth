import { describe, expect, it, vi } from "vitest";

const handleIr = vi.fn(async () => ({ id: "answered" }));

vi.mock("./driver/index.js", () => ({
  driver: { id: "claude-code", label: "Claude Code", models: {}, handleIr, loginFlow: async () => ({ url: "", complete: async () => null }) },
  CLAUDE_SETTINGS_SCHEMA: {},
  RETRY_KEYS: [],
}));

function contextSpy() {
  const provided: Record<string, unknown> = {};
  return {
    provided,
    context: { provide: vi.fn((key: string | { id: string }, value: unknown) => { provided[typeof key === "string" ? key : key.id] = value; }), paths: { home: "/tmp/home" } },
  };
}

describe("the claude-code-auth api plugin", () => {
  it("provides exactly the provider capability its manifest declares", async () => {
    const plugin = (await import("./plugin.js")).default;
    const { context, provided } = contextSpy();
    await plugin.activate(context as never);
    expect(Object.keys(provided)).toEqual(["provider"]);
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

  it("deactivates without throwing", async () => {
    const plugin = (await import("./plugin.js")).default;
    expect(plugin.deactivate()).toBeUndefined();
  });
});
