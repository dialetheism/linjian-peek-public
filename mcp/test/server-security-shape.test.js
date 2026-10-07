import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const source = fs.readFileSync(path.join(here, "..", "server.js"), "utf8");

test("all externally callable MCP transports require authentication", () => {
  assert.match(source, /app\.post\("\/mcp", requireMcpAuth,/);
  assert.match(source, /app\.get\("\/mcp", requireMcpAuth,/);
  assert.match(source, /app\.post\("\/mcp-wallet", requireMcpAuth,/);
  assert.match(source, /app\.get\("\/mcp-wallet", requireMcpAuth,/);
  assert.match(source, /app\.get\("\/sse", requireMcpAuth,/);
});

test("public health response does not expose backend URLs", () => {
  const start = source.indexOf('app.get("/health"');
  const end = source.indexOf("\n}));", start);
  assert.notEqual(start, -1);
  assert.notEqual(end, -1);
  const healthRoute = source.slice(start, end + 5);
  assert.equal(healthRoute.includes("configured_linjian_url:"), false);
  assert.equal(healthRoute.includes("effective_linjian_url:"), false);
  assert.equal(healthRoute.includes("fallback_linjian_urls:"), false);
  assert.match(healthRoute, /mcp_auth_required:\s*true/);
  assert.match(healthRoute, /mcp_access_token_configured:\s*Boolean\(MCP_ACCESS_TOKEN\)/);
});

test("CORS allows supported authentication headers", () => {
  assert.match(source, /"X-MCP-Token"/);
  assert.match(source, /"X-Auth-Token"/);
  assert.match(source, /"X-Linjian-Token"/);
});
