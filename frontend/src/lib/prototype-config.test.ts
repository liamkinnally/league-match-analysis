import { expect, it, vi } from "vitest";
vi.mock("server-only", () => ({}));
import { publicContactEmail } from "./prototype-config";

it("uses only the explicitly configured public contact address", () => {
  vi.stubEnv("PROTOTYPE_CONTACT_EMAIL", "prototype@example.test");
  expect(publicContactEmail()).toBe("prototype@example.test");
});

it.each(["", "unset", "name@example.test\nBcc: other@example.test", "javascript:alert(1)"])(
  "does not publish missing or invalid contact configuration", (value) => {
    vi.stubEnv("PROTOTYPE_CONTACT_EMAIL", value);
    expect(publicContactEmail()).toBeNull();
  },
);
