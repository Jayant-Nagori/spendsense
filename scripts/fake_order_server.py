#!/usr/bin/env python3
"""
Local fake of the Swiggy / Zomato / Zepto MCP + OAuth servers, for demoing "Connected apps" on the emulator or
simulator (debug builds: Settings > Connected apps > Developer > Use fake order server).

    python3 scripts/fake_order_server.py [--port 8787]

Android emulator reaches it at http://10.0.2.2:8787, the iOS simulator at http://127.0.0.1:8787.
Implements: 401 + WWW-Authenticate -> RFC 9728 resource metadata -> RFC 8414 AS metadata, RFC 7591 registration,
an /authorize page with an Approve button (redirects to the app's custom scheme), PKCE S256 checked at /token,
refresh, revoke, and MCP over Streamable HTTP (Swiggy answers as SSE, the others as JSON). Orders are synthetic and
always "placed a few minutes ago" so a payment logged now matches. Write tools are listed but refuse to run, and
every tools/call name is printed so you can see only read tools are used.
"""
import argparse, base64, hashlib, html, json, secrets, time, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CLIENTS, CODES, ACCESS, REFRESH = {}, {}, set(), set()
AUTO_APPROVE = False

ORDERS = {
    "swiggy/food": [dict(order_id="SW-55120", restaurant_name="Punjabi Grill", total_amount=486.0, minutes_ago=4,
                         items=[dict(name="Butter Chicken", quantity=1, price=320), dict(name="Butter Naan", quantity=2, price=83)])],
    "swiggy/im": [dict(order_id="IM-7781", store_name="Instamart", total_amount=349.0, minutes_ago=30,
                       items=[dict(name="Amul Taaza Milk 1L", quantity=2, price=68), dict(name="Brown Bread", quantity=1, price=55), dict(name="Bananas (6)", quantity=1, price=158)])],
    "zomato/mcp": [dict(order_id="ZO-90311", restaurant_name="Meghana Foods", total_amount=612.4, minutes_ago=6,
                        items=[dict(name="Chicken Biryani", quantity=1, price=389), dict(name="Gobi Manchurian", quantity=1, price=189)])],
    "zepto/mcp": [dict(order_id="ZP-4410", store_name="Zepto", total_amount=254.0, minutes_ago=8,
                       items=[dict(name="Maggi Masala 4-pack", quantity=1, price=96), dict(name="Coke Zero 750ml", quantity=2, price=79)])],
}

TOOLS = [
    dict(name="search_restaurants", description="Search restaurants", inputSchema=dict(type="object", properties=dict(query=dict(type="string")), required=["query"])),
    dict(name="add_to_cart", description="Add an item to the cart", inputSchema=dict(type="object", properties={})),
    dict(name="place_order", description="Places the order in the cart", inputSchema=dict(type="object", properties={})),
    dict(name="get_order_history", description="Your past orders", annotations=dict(readOnlyHint=True),
         inputSchema=dict(type="object", properties=dict(limit=dict(type="integer"), from_date=dict(type="string")))),
    dict(name="get_order_details", description="One past order", annotations=dict(readOnlyHint=True),
         inputSchema=dict(type="object", properties=dict(order_id=dict(type="string")), required=["order_id"])),
]


def b64url(b): return base64.urlsafe_b64encode(b).decode().rstrip("=")


class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *a):
        print("  " + self.command + " " + self.path.split("?")[0])

    @property
    def base(self):
        return "http://" + self.headers.get("Host", "127.0.0.1:8787")

    def send(self, code, body, ctype="application/json", headers=None):
        data = body if isinstance(body, bytes) else (json.dumps(body) if not isinstance(body, str) else body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n).decode() if n else ""

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = dict(urllib.parse.parse_qsl(u.query))
        if u.path.startswith("/.well-known/oauth-protected-resource/"):
            res = u.path[len("/.well-known/oauth-protected-resource"):]
            return self.send(200, dict(resource=self.base + res, authorization_servers=[self.base + "/auth"]))
        if u.path == "/.well-known/oauth-authorization-server/auth":
            a = self.base + "/auth"
            return self.send(200, dict(issuer=a, authorization_endpoint=a + "/authorize", token_endpoint=a + "/token",
                                       registration_endpoint=a + "/register", revocation_endpoint=a + "/revoke",
                                       code_challenge_methods_supported=["S256"], scopes_supported=["mcp:tools", "tools:read"],
                                       token_endpoint_auth_methods_supported=["none"], authorization_response_iss_parameter_supported=True))
        if u.path == "/auth/authorize":
            if q.get("client_id") not in CLIENTS or CLIENTS[q["client_id"]] != q.get("redirect_uri") or q.get("code_challenge_method") != "S256":
                return self.send(400, "<h1>Bad request</h1>", "text/html")
            approve = "/auth/approve?" + urllib.parse.urlencode(q)
            if AUTO_APPROVE:  # unattended QA: behave as if the user pressed Approve
                self.send_response(302); self.send_header("Location", approve); self.send_header("Content-Length", "0"); self.end_headers()
                return
            page = f"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="font-family:-apple-system,Roboto,sans-serif;background:#111;color:#eee;padding:32px">
<p style="color:#E5FE40;letter-spacing:2px;font-size:12px">FAKE SERVER (DEBUG)</p>
<h2>Sign in to your account</h2><p>SpendSense wants to <b>read your past orders</b> ({html.escape(q.get('scope',''))}).</p>
<a href="{html.escape(approve)}" style="display:block;background:#fff;color:#000;text-align:center;padding:16px;font-weight:700;text-decoration:none;margin-top:24px">APPROVE</a>
</body>"""
            return self.send(200, page, "text/html")
        if u.path == "/auth/approve":
            code = secrets.token_urlsafe(16)
            CODES[code] = (q["client_id"], q["redirect_uri"], q["code_challenge"])
            loc = q["redirect_uri"] + "?" + urllib.parse.urlencode(dict(code=code, state=q["state"], iss=self.base + "/auth"))
            self.send_response(302); self.send_header("Location", loc); self.send_header("Content-Length", "0"); self.end_headers()
            return
        self.send(404, dict(error="not_found"))

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        raw = self.body()
        if u.path == "/auth/register":
            o = json.loads(raw)
            cid = "fake-" + secrets.token_hex(6)
            CLIENTS[cid] = o["redirect_uris"][0]
            print("  registered client for", o["redirect_uris"][0], "scope", o.get("scope"))
            return self.send(201, dict(client_id=cid, token_endpoint_auth_method="none"))
        if u.path == "/auth/token":
            f = dict(urllib.parse.parse_qsl(raw))
            if f.get("grant_type") == "authorization_code":
                g = CODES.pop(f.get("code"), None)
                if not g or g[0] != f.get("client_id") or g[1] != f.get("redirect_uri") or b64url(hashlib.sha256(f.get("code_verifier", "").encode()).digest()) != g[2]:
                    return self.send(400, dict(error="invalid_grant"))
                print("  PKCE verified, issuing tokens")
            elif f.get("grant_type") == "refresh_token":
                if f.get("refresh_token") not in REFRESH:
                    return self.send(400, dict(error="invalid_grant"))
            else:
                return self.send(400, dict(error="unsupported_grant_type"))
            at, rt = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
            ACCESS.add(at); REFRESH.add(rt)
            return self.send(200, dict(access_token=at, token_type="Bearer", expires_in=3600, refresh_token=rt))
        if u.path == "/auth/revoke":
            t = dict(urllib.parse.parse_qsl(raw)).get("token")
            ACCESS.discard(t); REFRESH.discard(t)
            return self.send(200, "")
        key = u.path.strip("/")
        if key in ORDERS:
            return self.mcp(key, raw)
        self.send(404, dict(error="not_found"))

    def mcp(self, key, raw):
        auth = (self.headers.get("Authorization") or "").removeprefix("Bearer ")
        if auth not in ACCESS:
            return self.send(401, dict(error="invalid_token"), headers={
                "WWW-Authenticate": f'Bearer error="invalid_token", resource_metadata="{self.base}/.well-known/oauth-protected-resource/{key}", scope="mcp:tools"'})
        msg = json.loads(raw)
        if "id" not in msg:
            return self.send(202, "")
        m = msg.get("method")
        if m == "initialize":
            result = dict(protocolVersion="2025-06-18", capabilities=dict(tools={}), serverInfo=dict(name="fake-" + key, version="1"))
        elif m == "tools/list":
            result = dict(tools=TOOLS)
        elif m == "tools/call":
            name = msg["params"]["name"]
            print("  tools/call", name)
            if name not in ("get_order_history", "get_order_details"):
                result = dict(isError=True, content=[dict(type="text", text="refused")])
            else:
                now = time.time()
                orders = [dict({k: v for k, v in o.items() if k != "minutes_ago"},
                               placed_at=time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime(now - o["minutes_ago"] * 60)), status="DELIVERED")
                          for o in ORDERS[key]]
                result = dict(content=[dict(type="text", text=json.dumps(dict(data=dict(orders=orders))))])
        else:
            return self.send(200, dict(jsonrpc="2.0", id=msg["id"], error=dict(code=-32601, message="not found")))
        reply = dict(jsonrpc="2.0", id=msg["id"], result=result)
        hdr = {"Mcp-Session-Id": "fake-session"} if m == "initialize" else {}
        if key.startswith("swiggy"):
            sse = "event: message\ndata: " + json.dumps(reply) + "\n\n"
            return self.send(200, sse, "text/event-stream", hdr)
        self.send(200, reply, headers=hdr)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--auto-approve", action="store_true", help="skip the Approve page (unattended QA)")
    a = ap.parse_args()
    AUTO_APPROVE = a.auto_approve
    print(f"fake order server on http://{a.bind}:{a.port}  (emulator: http://10.0.2.2:{a.port})")
    ThreadingHTTPServer((a.bind, a.port), H).serve_forever()
