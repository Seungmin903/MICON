"""Read-only candidate health gate; never deploys, changes routing, or touches SQL."""

import argparse
import ipaddress
import json
import math
import time
from http.client import HTTPException
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_url(url):
    try:
        parsed = urlsplit(url)
        host = parsed.hostname
        port = parsed.port
        loopback = host == "localhost" or ipaddress.ip_address(host).is_loopback
    except (ValueError, TypeError):
        raise ValueError("Use an explicit loopback candidate URL") from None
    if (
        parsed.scheme not in ("http", "https")
        or not loopback
        or port is None or port == 0
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path != "/actuator/health/readiness"
    ):
        raise ValueError("Use loopback:port/actuator/health/readiness without credentials or query")
    return url


def check_once(url, timeout):
    """Return a safe reason, not response bodies, tokens, or raw exceptions."""
    validate_url(url)
    opener = build_opener(ProxyHandler({}), NoRedirect())
    request = Request(url, headers={"Accept": "application/json"})
    try:
        with opener.open(request, timeout=timeout) as response:
            if response.status != 200:
                return False, "http_status"
            if response.headers.get_content_type() != "application/json":
                return False, "content_type"
            payload = response.read(65537)
            if len(payload) > 65536:
                return False, "response_too_large"
            data = json.loads(payload)
            if not isinstance(data, dict) or data.get("status") != "UP":
                return False, "not_up"
            return True, "ready"
    except HTTPError as error:
        error.close()
        return False, "http_status"
    except (URLError, OSError, TimeoutError, HTTPException):
        return False, "connection_or_timeout"
    except (ValueError, UnicodeError):
        return False, "invalid_json"


def wait_for_ready(url, deadline=60, interval=2, timeout=5, required_successes=3, *,
                   probe=check_once, clock=time.monotonic, sleep=time.sleep,
                   report=print):
    validate_url(url)
    if not all(math.isfinite(x) and x > 0 for x in (deadline, interval, timeout)):
        raise ValueError("Timing values must be finite and positive")
    if deadline > 60 or timeout > deadline or interval > deadline:
        raise ValueError("Deadline must be <=60s and bound timeout and interval")
    if (
        not isinstance(required_successes, int)
        or isinstance(required_successes, bool)
        or not 1 <= required_successes <= 10
    ):
        raise ValueError("Required successes must be an integer from 1 to 10")
    start = clock()
    attempt = 0
    success_streak = 0
    while clock() - start < deadline:
        attempt += 1
        remaining = deadline - (clock() - start)
        if remaining <= 0:
            break
        ready, reason = probe(url, min(timeout, remaining))
        elapsed = clock() - start
        success_streak = success_streak + 1 if ready else 0
        report(
            f"health attempt={attempt} result={reason} "
            f"success_streak={success_streak}/{required_successes} elapsed_s={elapsed:.2f}"
        )
        if success_streak >= required_successes and elapsed <= deadline:
            return True
        remaining = deadline - (clock() - start)
        if remaining > 0:
            sleep(min(interval, remaining))
    report("health result=failed action=keep_existing_route")
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--deadline", type=float, default=60)
    parser.add_argument("--interval", type=float, default=2)
    parser.add_argument("--timeout", type=float, default=5)
    parser.add_argument("--required-successes", type=int, default=3)
    args = parser.parse_args()
    try:
        return 0 if wait_for_ready(
            args.url,
            args.deadline,
            args.interval,
            args.timeout,
            args.required_successes,
        ) else 1
    except ValueError:
        print("health result=invalid_configuration")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
