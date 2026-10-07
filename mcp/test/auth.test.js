import test from "node:test";
import assert from "node:assert/strict";

import { createMcpAuthMiddleware, extractMcpToken, mcpAuthState, tokensEqual } from "../auth.js";

const SECRET = "test-secret-that-is-long-enough";

test("extracts bearer token", () => {
  assert.equal(extractMcpToken({ headers: { authorization: `Bearer ${SECRET}` }, url: "/mcp" }), SECRET);
});

test("accepts dedicated and compatibility headers", () => {
  assert.equal(extractMcpToken({ headers: { "x-mcp-token": SECRET }, url: "/mcp" }), SECRET);
  assert.equal(extractMcpToken({ headers: { "x-auth-token": SECRET }, url: "/mcp" }), SECRET);
  assert.equal(extractMcpToken({ headers: { "x-linjian-token": SECRET }, url: "/mcp" }), SECRET);
});

test("accepts access_token and token query fallbacks", () => {
  assert.equal(extractMcpToken({ headers: {}, originalUrl: `/mcp?access_token=${SECRET}` }), SECRET);
  assert.equal(extractMcpToken({ headers: {}, originalUrl: `/mcp?token=${SECRET}` }), SECRET);
});

test("header token takes precedence over query token", () => {
  assert.equal(extractMcpToken({ headers: { authorization: `Bearer ${SECRET}` }, originalUrl: "/mcp?token=wrong" }), SECRET);
});

test("compares tokens without accepting empty or length-mismatched values", () => {
  assert.equal(tokensEqual(SECRET, SECRET), true);
  assert.equal(tokensEqual("", ""), false);
  assert.equal(tokensEqual("short", SECRET), false);
  assert.equal(tokensEqual(`${SECRET}x`, SECRET), false);
});

test("fails closed when secret is absent", () => {
  assert.deepEqual(mcpAuthState({ headers: {}, url: "/mcp" }, ""), {
    ok: false,
    error: "MCP_ACCESS_TOKEN_NOT_CONFIGURED",
    status: 503
  });
});

test("rejects missing and wrong credentials", () => {
  assert.equal(mcpAuthState({ headers: {}, url: "/mcp" }, SECRET).status, 401);
  assert.equal(mcpAuthState({ headers: { authorization: "Bearer wrong" }, url: "/mcp" }, SECRET).status, 401);
});

test("accepts valid credentials", () => {
  assert.deepEqual(mcpAuthState({ headers: { authorization: `Bearer ${SECRET}` }, url: "/mcp" }, SECRET), {
    ok: true,
    status: 200
  });
});

test("middleware returns a no-store bearer challenge without echoing secrets", () => {
  const headers = {};
  let status = 0;
  let payload = null;
  let nextCalled = false;
  const res = {
    setHeader(name, value) { headers[name] = value; },
    status(value) { status = value; return this; },
    json(value) { payload = value; return this; }
  };
  createMcpAuthMiddleware(SECRET)({ headers: {}, url: "/mcp" }, res, () => { nextCalled = true; });
  assert.equal(nextCalled, false);
  assert.equal(status, 401);
  assert.equal(headers["Cache-Control"], "no-store");
  assert.equal(headers["Referrer-Policy"], "no-referrer");
  assert.match(headers["WWW-Authenticate"], /^Bearer /);
  assert.equal(JSON.stringify(payload).includes(SECRET), false);
});

test("middleware calls next only for a valid token", () => {
  let nextCalled = false;
  const res = { setHeader() {}, status() { return this; }, json() { return this; } };
  createMcpAuthMiddleware(SECRET)(
    { headers: { authorization: `Bearer ${SECRET}` }, url: "/mcp" },
    res,
    () => { nextCalled = true; }
  );
  assert.equal(nextCalled, true);
});
