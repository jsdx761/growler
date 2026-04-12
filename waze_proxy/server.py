#!/usr/bin/env python3
"""
Waze GeoRSS proxy server.

Proxies georss API calls by creating a fresh Chrome browser context for
each request. Each context loads the Waze live map page, establishes a
reCAPTCHA Enterprise session, makes the API call, and is then discarded.
This avoids reCAPTCHA session degradation in container environments.

Usage:
    python server.py [--port 8080] [--host 0.0.0.0] [--api-key SECRET]

The Android app calls:
    GET /georss?bottom=LAT&left=LNG&top=LAT&right=LNG&env=na&types=alerts
"""

import argparse
import functools
import hmac
import logging
import os
import queue
import signal
import threading
from flask import Flask, request, jsonify
from playwright.sync_api import sync_playwright
from waitress import serve

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("waze_proxy")

app = Flask(__name__)

WAZE_LIVE_MAP = "https://www.waze.com/live-map"
GEORSS_PATH = "/live-map/api/georss"
RECAPTCHA_TIMEOUT = 15

_api_key = None
_request_queue = queue.Queue(maxsize=10)
_browser_ready = threading.Event()
_shutdown = threading.Event()

FETCH_JS = """(url) => {
    return new Promise((resolve) => {
        function doFetch(token) {
            const opts = {credentials: 'same-origin'};
            if (token) opts.headers = {'X-Recaptcha-Token': token};
            fetch(url, opts)
                .then(r => {
                    if (!r.ok) return r.text().then(body => {
                        resolve({error: 'HTTP ' + r.status, status: r.status});
                    });
                    return r.text();
                })
                .then(t => { if (t) resolve({data: t}); })
                .catch(e => resolve({error: e.message}));
        }
        try {
            if (typeof grecaptcha !== 'undefined' && typeof grecaptcha.enterprise !== 'undefined') {
                const html = document.documentElement.outerHTML || '';
                let siteKey = '';
                const i = html.indexOf('recaptchaSessionSiteKey');
                if (i !== -1) {
                    const sub = html.substring(i, i + 120);
                    const m = sub.match(/["']\\s*[,:]\\s*["']([A-Za-z0-9_-]{20,})/);
                    if (m) siteKey = m[1];
                }
                if (siteKey) {
                    let done = false;
                    setTimeout(() => { if (!done) { done = true; doFetch(null); } }, 5000);
                    grecaptcha.enterprise.execute(siteKey, {action: 'alerts'})
                        .then(t => { if (!done) { done = true; doFetch(t); } })
                        .catch(() => { if (!done) { done = true; doFetch(null); } });
                } else {
                    doFetch(null);
                }
            } else {
                doFetch(null);
            }
        } catch(e) { doFetch(null); }
    });
}"""


def browser_loop():
    """Run in a dedicated thread. Owns the Playwright browser and
    processes fetch requests from the queue. Each request gets a fresh
    browser context (clean cookies, clean reCAPTCHA session)."""
    log.info("Starting Playwright browser")
    pw = sync_playwright().start()
    browser = pw.chromium.launch(
        headless=False,
        channel="chrome",
    )
    _browser_ready.set()
    log.info("Browser ready")

    while not _shutdown.is_set():
        try:
            qs, result_event, result_holder = _request_queue.get(timeout=1)
        except queue.Empty:
            continue

        url = f"{GEORSS_PATH}?{qs}"
        context = None
        try:
            context = browser.new_context(
                viewport={"width": 1280, "height": 800},
            )
            page = context.new_page()

            log.info("Loading page")
            page.goto(WAZE_LIVE_MAP, wait_until="load")

            try:
                page.wait_for_function(
                    "typeof grecaptcha !== 'undefined' && typeof grecaptcha.enterprise !== 'undefined'",
                    timeout=RECAPTCHA_TIMEOUT * 1000,
                )
            except Exception:
                log.warning("reCAPTCHA not detected, fetching without token")

            # Let the reCAPTCHA session warm up — the session cookie
            # needs a few seconds to establish a non-zero score
            page.wait_for_timeout(3000)

            log.info("Fetching %s", url)
            result = page.evaluate(FETCH_JS, url)

            if "error" in result:
                log.warning("Fetch error: %s", result.get("error"))
                result_holder["error"] = result.get("error")
                result_holder["status"] = result.get("status", 502)
            else:
                result_holder["data"] = result.get("data")

        except Exception as e:
            log.error("Exception: %s", e)
            result_holder["error"] = str(e)
        finally:
            if context is not None:
                try:
                    context.close()
                except Exception:
                    pass
            result_event.set()

    log.info("Shutting down browser")
    browser.close()
    pw.stop()


def require_api_key(f):
    @functools.wraps(f)
    def decorated(*args, **kwargs):
        if _api_key is not None:
            provided = request.headers.get("X-API-Key", "")
            if not hmac.compare_digest(provided, _api_key):
                return jsonify({"error": "Unauthorized"}), 401
        return f(*args, **kwargs)
    return decorated


@app.route("/georss")
@require_api_key
def georss():
    if not _browser_ready.is_set():
        return jsonify({"error": "Browser not ready"}), 503

    qs = request.query_string.decode("utf-8")
    if not qs:
        return jsonify({"error": "Missing query parameters"}), 400

    result_event = threading.Event()
    result_holder = {}
    try:
        _request_queue.put_nowait((qs, result_event, result_holder))
    except queue.Full:
        return jsonify({"error": "Server busy"}), 503
    result_event.wait(timeout=30)

    if not result_event.is_set():
        return jsonify({"error": "Timeout"}), 504

    if "error" in result_holder:
        status = result_holder.get("status", 502)
        return jsonify({"error": result_holder["error"]}), status

    return app.response_class(
        response=result_holder["data"],
        status=200,
        mimetype="application/json",
    )


@app.route("/health")
def health():
    return jsonify({
        "status": "ok",
        "browser_ready": _browser_ready.is_set(),
    })


def main():
    global _api_key

    parser = argparse.ArgumentParser(description="Waze GeoRSS proxy server")
    parser.add_argument("--host", default="0.0.0.0", help="Bind address")
    parser.add_argument("--port", type=int, default=8080, help="Port")
    parser.add_argument("--api-key", default=None,
                        help="Require this key in the X-API-Key header")
    args = parser.parse_args()

    _api_key = args.api_key or os.environ.get("WAZE_PROXY_API_KEY")
    if _api_key:
        log.info("API key authentication enabled")

    def shutdown_handler(sig, frame):
        log.info("Received signal %s, shutting down", sig)
        _shutdown.set()

    signal.signal(signal.SIGINT, shutdown_handler)
    signal.signal(signal.SIGTERM, shutdown_handler)

    t = threading.Thread(target=browser_loop, daemon=True)
    t.start()

    log.info("Starting server on %s:%d", args.host, args.port)
    serve(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
