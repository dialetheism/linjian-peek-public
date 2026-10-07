import { timingSafeEqual } from "node:crypto";

function cleanToken(value = "") {
  if (Array.isArray(value)) value = value[0] || "";
  return String(value || "").trim();
}

function bearerToken(value = "") {
  const match = cleanToken(value).match(/^Bearer\s+(.+)$/i);
  return cleanToken(match?.[1] || "");
}

function queryToken(req) {
  try {
    const url = new URL(req?.originalUrl || req?.url || "/", "http://mcp.local");
    return cleanToken(url.searchParams.get("access_token") || url.searchParams.get("token") || "");
  } catch {
    return "";
  }
}

export function extractMcpToken(req = {}) {
  const headers = req.headers || {};
  return (
    bearerToken(headers.authorization) ||
    cleanToken(headers["x-mcp-token"]) ||
    cleanToken(headers["x-auth-token"]) ||
    cleanToken(headers["x-linjian-token"]) ||
    queryToken(req)
  );
}

export function tokensEqual(supplied = "", expected = "") {
  const left = Buffer.from(cleanToken(supplied), "utf8");
  const right = Buffer.from(cleanToken(expected), "utf8");
  if (!left.length || left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}

export function mcpAuthState(req, expectedToken = "") {
  const expected = cleanToken(expectedToken);
  if (!expected) return { ok: false, error: "MCP_ACCESS_TOKEN_NOT_CONFIGURED", status: 503 };
  const supplied = extractMcpToken(req);
  if (!tokensEqual(supplied, expected)) return { ok: false, error: "MCP_AUTH_REQUIRED", status: 401 };
  return { ok: true, status: 200 };
}

export function createMcpAuthMiddleware(expectedToken = "") {
  return (req, res, next) => {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Referrer-Policy", "no-referrer");
    const state = mcpAuthState(req, expectedToken);
    if (state.ok) return next();
    if (state.status === 401) res.setHeader("WWW-Authenticate", 'Bearer realm="zhangxinchuang-mcp"');
    return res.status(state.status).json({
      jsonrpc: "2.0",
      error: { code: state.status === 401 ? -32001 : -32002, message: state.error },
      id: null
    });
  };
}
