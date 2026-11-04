"""Read-only Apple lyrics probe. Never prints or saves tokens or lyric text."""

import argparse
import base64
import heapq
import html.parser
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

APPLE_WEB = "https://music.apple.com"
APPLE_API = "https://amp-api.music.apple.com"
TTML_NS = "http://www.w3.org/ns/ttml"
ITUNES_NS = "http://music.apple.com/lyric-ttml-internal"
METADATA_NS = TTML_NS + "#metadata"
XML_NS = "http://www.w3.org/XML/1998/namespace"
MAX_JSON_DEPTH = 32
MAX_JSON_PATHS = 16
MAX_XML_ELEMENTS = 25000
MAX_XML_DEPTH = 64
JSON_PATH_KEYS = frozenset({
    "data", "attributes", "relationships", "lyrics", "syllable-lyrics", "ttml", "included",
})
EXCLUDED_ROLES = frozenset({"x-bg", "x-translation", "x-roman"})
MAX_WEB = 8 * 1024 * 1024
MAX_API = 2 * 1024 * 1024
MAX_TTML = 512 * 1024
TIMEOUT = 15
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"


class ProbeError(Exception):
    """Messages are fixed diagnostic codes, never remote content."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = urllib.request.build_opener(NoRedirect())


def emit(event, **fields):
    print(json.dumps({"event": event, **fields}, ensure_ascii=True), flush=True)


def read_url(url, headers=None, limit=MAX_API, public_redirects=3):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in
            {"music.apple.com", "amp-api.music.apple.com"} or
            parsed.username or parsed.password or parsed.port not in (None, 443)):
        raise ProbeError("unexpected_destination")
    request_headers = {"User-Agent": USER_AGENT, "Accept-Encoding": "identity"}
    request_headers.update(headers or {})
    request = urllib.request.Request(url, headers=request_headers)
    try:
        response = OPENER.open(request, timeout=TIMEOUT)
    except urllib.error.HTTPError as error:
        location = error.headers.get("Location")
        status = error.code
        error.close()
        # Only public website requests can follow same-origin redirects.
        # API requests and all requests carrying credentials never follow them.
        if (status in (301, 302, 303, 307, 308) and not headers and
                parsed.hostname == "music.apple.com" and location and public_redirects > 0):
            target = urllib.parse.urljoin(url, location)
            if urllib.parse.urlsplit(target).netloc == "music.apple.com":
                return read_url(target, None, limit, public_redirects - 1)
        return status, b""
    except (urllib.error.URLError, TimeoutError, OSError):
        raise ProbeError("network_failed") from None
    try:
        with response:
            raw = response.read(limit + 1)
            if len(raw) > limit:
                raise ProbeError("response_too_large")
            return response.status, raw
    except (TimeoutError, OSError):
        raise ProbeError("response_read_failed") from None


class ScriptLinks(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.urls = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        target = values.get("src") if tag == "script" else (
            values.get("href") if tag == "link" and
            values.get("rel") == "modulepreload" else None)
        if target:
            url = urllib.parse.urljoin(APPLE_WEB, target)
            parsed = urllib.parse.urlsplit(url)
            if parsed.hostname == "music.apple.com" and parsed.path.endswith(".js"):
                if url not in self.urls:
                    self.urls.append(url)


def bearer_candidates(source):
    candidates = []
    for token in set(re.findall(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", source)):
        try:
            payload = token.split(".")[1]
            claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
            expiry = claims.get("exp", 0)
            if isinstance(expiry, (int, float)) and expiry > time.time() + 60:
                candidates.append((expiry, token))
        except (ValueError, TypeError):
            continue
    return [token for _, token in sorted(candidates, reverse=True)]


def public_bearer():
    status, raw = read_url(APPLE_WEB + "/us/browse", limit=MAX_WEB)
    emit("web_page", status=status, bytes=len(raw))
    if status != 200:
        raise ProbeError("web_page_unavailable")
    page = raw.decode("utf-8")
    parser = ScriptLinks()
    parser.feed(page)
    urls = sorted(parser.urls, key=lambda url: (
        0 if "/index" in urllib.parse.urlsplit(url).path else 1))
    emit("web_assets", script_count=len(urls))
    candidates = bearer_candidates(page)
    for index, url in enumerate(urls[:8]):
        if candidates:
            break
        status, raw = read_url(url, limit=MAX_WEB)
        emit("web_asset", index=index, status=status, bytes=len(raw))
        if status == 200:
            candidates = bearer_candidates(raw.decode("utf-8"))
    if not candidates:
        raise ProbeError("public_bearer_not_found")
    emit("public_bearer", found=True)
    return candidates[0]


def classify_time(value):
    """Return a fixed format label and exact milliseconds, never the input.

    Use the Kotlin parser's lexical forms and signed 64-bit millisecond range.
    A recognized form with an excessive magnitude remains indeterminate.
    """
    seconds = re.fullmatch(r"([0-9]+)(?:\.([0-9]{1,3}))?s?", value)
    clock = re.fullmatch(r"(?:([0-9]{2,}):)?([0-9]{1,2}):([0-9]{2})(?:\.([0-9]{1,3}))?", value)
    if seconds:
        category = "seconds"
        whole = seconds[1]
        multiplier = 1000
        remainder = int((seconds[2] or "").ljust(3, "0"))
    elif clock and int(clock[2]) <= 59 and int(clock[3]) <= 59:
        category = "clock"
        whole = clock[1] or "0"
        multiplier = 3600000
        remainder = (int(clock[2]) * 60000 + int(clock[3]) * 1000 +
                     int((clock[4] or "").ljust(3, "0")))
    else:
        return "other", None
    # Bound conversion too: Python rejects excessively long decimal integers.
    whole = whole.lstrip("0") or "0"
    if len(whole) > 19:
        return category, None
    milliseconds = int(whole) * multiplier + remainder
    return category, milliseconds if milliseconds <= 9223372036854775807 else None


def ttml_structure(text):
    """One bounded XML walk; only fixed names, counts and enums leave it."""
    summary = {
        "timing": "unknown", "lines": 0, "timed_lines": 0,
        "timed_spans": 0, "spans_with_begin_and_end": 0,
        "elements": 0, "max_depth": 0,
        "namespaces": dict.fromkeys(("ttml", "metadata", "itunes", "xml", "other"), 0),
        "attributes": dict.fromkeys(("begin", "end", "dur", "timeContainer",
                                     "metadata_role", "metadata_agent", "xml_space"), 0),
        "roles": dict.fromkeys(("x-bg", "x-translation", "x-roman", "other"), 0),
        "time_formats": {name: dict.fromkeys(("seconds", "clock", "other"), 0)
                         for name in ("begin", "end", "dur")},
        "p_without_begin": 0, "nested_timed_spans": 0, "timed_divs": 0,
        "main_lines": 0, "main_start_indeterminate": 0, "main_interval_indeterminate": 0,
        "main_simultaneous_start_pairs": 0, "main_known_overlap_pairs": 0,
    }
    attribute_names = {
        "begin": "begin", "end": "end", "dur": "dur", "timeContainer": "timeContainer",
        "{" + METADATA_NS + "}role": "metadata_role",
        "{" + METADATA_NS + "}agent": "metadata_agent",
        "{" + XML_NS + "}space": "xml_space",
    }
    namespace_categories = {
        TTML_NS: "ttml", METADATA_NS: "metadata", ITUNES_NS: "itunes", XML_NS: "xml",
    }
    namespaces_seen = set()

    def namespace(uri):
        # Count distinct declared/used URIs, including unused declarations and
        # implicit xml: attributes. No namespace is not an unknown URI.
        if uri and uri not in namespaces_seen:
            namespaces_seen.add(uri)
            summary["namespaces"][namespace_categories.get(uri, "other")] += 1

    frames = []  # (excluded subtree, timed span ancestor, unresolved timing context)
    starts = {}
    intervals = []
    try:
        for event, node in ET.iterparse(io.StringIO(text), events=("start", "end", "start-ns")):
            if event == "start-ns":
                namespace(node[1])
                continue
            if event == "end":
                frames.pop()
                node.clear()
                continue
            depth = len(frames) + 1  # The root element has depth one.
            if depth > MAX_XML_DEPTH:
                raise ProbeError("ttml_depth_limit")
            if summary["elements"] == MAX_XML_ELEMENTS:
                raise ProbeError("ttml_element_limit")
            summary["elements"] += 1
            summary["max_depth"] = max(summary["max_depth"], depth)
            if depth == 1:
                if node.tag != "{" + TTML_NS + "}tt":
                    raise ProbeError("unexpected_ttml_root")
                timing = node.get("{" + ITUNES_NS + "}timing")
                summary["timing"] = timing if timing in ("Word", "Line", "None") else "unknown"
            for name in (node.tag, *node.attrib):
                if name.startswith("{"):
                    namespace(name[1:].split("}", 1)[0])
            for name, label in attribute_names.items():
                if name in node.attrib:
                    summary["attributes"][label] += 1
            times = {}
            for name in ("begin", "end", "dur"):
                if name in node.attrib:
                    category, milliseconds = classify_time(node.attrib[name])
                    summary["time_formats"][name][category] += 1
                    times[name] = milliseconds
            role = node.get("{" + METADATA_NS + "}role")
            if role is not None:
                summary["roles"][role if role in EXCLUDED_ROLES else "other"] += 1
            parent_excluded, parent_span, parent_context = frames[-1] if frames else (False, False, False)
            excluded = (parent_excluded or node.tag == "{" + TTML_NS + "}head" or
                        role in EXCLUDED_ROLES)
            is_p = node.tag == "{" + TTML_NS + "}p"
            is_span = node.tag == "{" + TTML_NS + "}span"
            is_div = node.tag == "{" + TTML_NS + "}div"
            timed = bool(times)  # Presence of begin, end or dur, including invalid values.
            if is_p:
                summary["lines"] += 1
                summary["timed_lines"] += "begin" in node.attrib
                summary["p_without_begin"] += "begin" not in node.attrib
            if is_span:
                summary["timed_spans"] += "begin" in node.attrib
                summary["spans_with_begin_and_end"] += "begin" in node.attrib and "end" in node.attrib
                summary["nested_timed_spans"] += timed and parent_span
            if is_div:
                summary["timed_divs"] += timed
            context = parent_context or "timeContainer" in node.attrib
            if is_p and not excluded:
                summary["main_lines"] += 1
                start, end = times.get("begin"), times.get("end")
                # Do not guess relative/sequence timing, duration resolution or
                # missing endpoints. Pair counts below cover known times only.
                if context or start is None:
                    summary["main_start_indeterminate"] += 1
                else:
                    starts[start] = starts.get(start, 0) + 1
                if context or start is None or end is None or end <= start or "dur" in node.attrib:
                    summary["main_interval_indeterminate"] += 1
                else:
                    intervals.append((start, end))
            frames.append((excluded, parent_span or (is_span and timed), context or timed))
    except ET.ParseError:
        raise ProbeError("invalid_ttml_xml") from None
    summary["main_simultaneous_start_pairs"] = sum(count * (count - 1) // 2 for count in starts.values())
    active_ends = []
    for start, end in sorted(intervals):
        # Half-open intervals: adjacent endpoints alone do not overlap.
        while active_ends and active_ends[0] <= start:
            heapq.heappop(active_ends)
        summary["main_known_overlap_pairs"] += len(active_ends)
        heapq.heappush(active_ends, end)
    return summary


def ttml_summary(text):
    if not isinstance(text, str) or len(text.encode("utf-8")) > MAX_TTML:
        raise ProbeError("invalid_ttml_size")
    if re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", text, re.IGNORECASE):
        raise ProbeError("ttml_declarations_rejected")
    return {"bytes": len(text.encode("utf-8")), **ttml_structure(text)}


def summarize_response(data):
    documents = []
    seen = {}
    paths_seen = set()
    flags = {"json_depth_limit_exceeded": False, "json_path_limit_exceeded": False}

    def visit(value, path=()):
        # Depth counts object keys and array steps; the response root is zero.
        if len(path) > MAX_JSON_DEPTH:
            flags["json_depth_limit_exceeded"] = True
            return
        if path and path[-1] == "ttml" and isinstance(value, str):
            if value not in seen:
                try:
                    entry = ttml_summary(value)
                except ProbeError as error:
                    entry = {"error": str(error)}
                entry.update(paths=[], occurrences=0)
                seen[value] = entry
                documents.append(entry)
            entry = seen[value]
            entry["occurrences"] += 1
            if path not in paths_seen:
                if len(paths_seen) == MAX_JSON_PATHS:
                    flags["json_path_limit_exceeded"] = True
                    return
                paths_seen.add(path)
            safe_path = list(path)
            if safe_path not in entry["paths"]:
                entry["paths"].append(safe_path)
            return
        if isinstance(value, dict):
            for key, child in value.items():
                visit(child, path + (key if key in JSON_PATH_KEYS else "<other>",))
        elif isinstance(value, list):
            for child in value:
                visit(child, path + ("[]",))

    visit(data)
    if not documents and flags["json_depth_limit_exceeded"]:
        # Keep truncation visible even if every TTML was beyond the depth cap.
        documents.append({"error": "json_depth_limit", "paths": [], "occurrences": 0})
    for entry in documents:
        entry.update(flags)
    return documents


def probe(song_id, storefront, user_token=None):
    bearer = public_bearer()
    headers = {"Authorization": "Bearer " + bearer, "Origin": APPLE_WEB,
               "Referer": APPLE_WEB + "/", "Accept": "application/json"}
    if user_token:
        headers["media-user-token"] = user_token
        status, raw = read_url(APPLE_API + "/v1/me/storefront", headers)
        emit("storefront", status=status)
        if status != 200:
            raise ProbeError("user_authentication_failed")
        response = json.loads(raw)
        account_storefront = response["data"][0]["id"]
        if not re.fullmatch(r"[a-z]{2}", account_storefront):
            raise ProbeError("unexpected_storefront")
        storefront = account_storefront
    path = f"/v1/catalog/{storefront}/songs/{song_id}"
    queries = [
        ("catalog_relationships", path + "?" + urllib.parse.urlencode({
            "include[songs]": "albums,lyrics,syllable-lyrics", "l": "ja-JP"})),
        ("syllable_lyrics", path + "/syllable-lyrics"),
        ("lyrics", path + "/lyrics"),
    ]
    document_count = 0
    for label, request_path in queries:
        status, raw = read_url(APPLE_API + request_path, headers)
        summaries = summarize_response(json.loads(raw)) if status == 200 else []
        document_count += sum("error" not in entry for entry in summaries)
        emit("api", route=label, status=status, bytes=len(raw),
             ttml=summaries, user_authenticated=bool(user_token))
    emit("result", song_id=song_id, ttml_documents=document_count,
         user_authenticated=bool(user_token))
    return 0 if document_count else 2


def request_user_token():
    # A local masked dialog is usable from Codex without sending the token to chat.
    # The value is not written to a file, command line or diagnostic output.
    import tkinter as tk
    from tkinter import simpledialog
    window = tk.Tk()
    window.withdraw()
    window.attributes("-topmost", True)
    try:
        token = simpledialog.askstring(
            "Apple Music lyrics probe",
            "Apple Music Web の media-user-token を貼り付けてください。\n"
            "入力は非表示です。ファイルへ保存せず、Apple の API にのみ送信します。\n"
            "このチャットには貼り付けないでください。",
            show="*", parent=window,
        )
    finally:
        window.destroy()
    if token is None:
        raise ProbeError("cancelled")
    token = token.strip()
    if not token or "\r" in token or "\n" in token:
        raise ProbeError("invalid_user_token")
    return token


def contract_self_test():
    """Synthetic contract cases; each case reports only a fixed name/count."""
    from contextlib import contextmanager, redirect_stdout
    cases = 0

    @contextmanager
    def case(name):
        nonlocal cases
        try:
            yield
        except Exception:
            emit("contract_self_test", passed=False, case=name, cases=cases + 1, failures=1)
            raise
        cases += 1

    def xml(content="", attributes=""):
        return '<tt xmlns="' + TTML_NS + '"' + attributes + '>' + content + '</tt>'

    def xml_error(text, expected):
        try:
            ttml_summary(text)
        except ProbeError as error:
            assert str(error) == expected
        else:
            raise AssertionError("expected_xml_error")

    sample = xml('<p begin="1" end="2">PRIVATE_CONTRACT_TEXT</p>')

    def nested_response(depth, arrays=False, text=sample):
        value = {"ttml": text}
        for _ in range(depth - 1):
            value = [value] if arrays else {"data": value}
        return value

    with case("json_direct"):
        result = summarize_response({"ttml": sample})
        assert len(result) == 1 and result[0]["paths"] == [["ttml"]]
        assert result[0]["occurrences"] == 1
        assert not result[0]["json_depth_limit_exceeded"]
        assert not result[0]["json_path_limit_exceeded"]

    with case("json_relationships"):
        result = summarize_response({
            "data": [{"relationships": {
                "lyrics": {"data": [{"attributes": {"ttml": sample}}]},
                "syllable-lyrics": {"data": [{"attributes": {"ttml": sample}}]},
            }}], "included": [{"attributes": {"ttml": sample}}],
        })
        assert len(result) == 1 and result[0]["occurrences"] == 3
        assert result[0]["paths"] == [
            ["data", "[]", "relationships", "lyrics", "data", "[]", "attributes", "ttml"],
            ["data", "[]", "relationships", "syllable-lyrics", "data", "[]", "attributes", "ttml"],
            ["included", "[]", "attributes", "ttml"],
        ]

    with case("json_arrays"):
        result = summarize_response([[{"ttml": sample}, {"ttml": sample}]])
        assert len(result) == 1 and result[0]["occurrences"] == 2
        assert result[0]["paths"] == [["[]", "[]", "ttml"]]
        assert summarize_response({"ttml": [sample], "data": sample}) == []

    with case("json_duplicates"):
        second = xml('<p begin="2" end="3">SECOND_CONTRACT_TEXT</p>')
        result = summarize_response({"ttml": sample, "data": [
            {"attributes": {"ttml": sample}}, {"attributes": {"ttml": second}},
            {"attributes": {"ttml": sample}},
        ]})
        assert len(result) == 2
        assert result[0]["occurrences"] == 3 and result[1]["occurrences"] == 1
        assert result[0]["paths"] == [["ttml"], ["data", "[]", "attributes", "ttml"]]
        assert result[1]["paths"] == [["data", "[]", "attributes", "ttml"]]

    with case("json_redaction"):
        result = summarize_response({
            "Q": {"ttml": sample}, "PRIVATE_JSON_KEY_SECRET": {"ttml": sample},
            "": {"ttml": sample}, "[]": {"ttml": sample},
        })
        assert len(result) == 1 and result[0]["occurrences"] == 4
        assert result[0]["paths"] == [["<other>", "ttml"]]
        assert "PRIVATE_JSON_KEY_SECRET" not in json.dumps(result)

    with case("json_depth_boundary"):
        result = summarize_response(nested_response(MAX_JSON_DEPTH))
        assert result[0]["paths"] == [["data"] * 31 + ["ttml"]]
        assert not result[0]["json_depth_limit_exceeded"]
        for depth in (MAX_JSON_DEPTH + 1, 1200):
            result = summarize_response(nested_response(depth))
            assert result == [{"error": "json_depth_limit", "paths": [], "occurrences": 0,
                               "json_depth_limit_exceeded": True, "json_path_limit_exceeded": False}]
        result = summarize_response({"ttml": sample, "data": nested_response(MAX_JSON_DEPTH)})
        assert len(result) == 1 and result[0]["occurrences"] == 1
        assert result[0]["json_depth_limit_exceeded"]

    with case("json_array_depth_boundary"):
        result = summarize_response(nested_response(MAX_JSON_DEPTH, arrays=True))
        assert result[0]["paths"] == [["[]"] * 31 + ["ttml"]]
        assert not result[0]["json_depth_limit_exceeded"]
        result = summarize_response(nested_response(MAX_JSON_DEPTH + 1, arrays=True))
        assert result[0]["error"] == "json_depth_limit" and result[0]["json_depth_limit_exceeded"]

    with case("json_path_boundary"):
        branches = [nested_response(depth) for depth in range(1, MAX_JSON_PATHS + 1)]
        result = summarize_response(branches)
        expected = [["[]"] + ["data"] * depth + ["ttml"] for depth in range(16)]
        assert len(result) == 1 and result[0]["paths"] == expected
        assert result[0]["occurrences"] == 16 and not result[0]["json_path_limit_exceeded"]
        result = summarize_response(branches + [nested_response(17), {"ttml": sample}])
        assert len(result) == 1 and result[0]["paths"] == expected
        assert result[0]["occurrences"] == 18 and result[0]["json_path_limit_exceeded"]
        result = summarize_response([nested_response(depth, text=xml('<p>' + str(depth) + '</p>'))
                                     for depth in range(1, 18)])
        assert len(result) == 17 and sum(len(entry["paths"]) for entry in result) == 16
        assert result[-1]["paths"] == [] and all(entry["json_path_limit_exceeded"] for entry in result)

    with case("xml_structure_counts"):
        result = ttml_summary(xml(
            '<head><m:agent xml:id="PRIVATE_AGENT_ID"/></head><body xml:space="preserve">'
            '<div begin="1s" end="5s" dur="4s" timeContainer="par">'
            '<p m:role="x-bg" m:agent="PRIVATE_AGENT" begin="1s">'
            '<span end="2s"><span dur="0.2s"/></span></p><u:item u:value="PRIVATE_VALUE"/>'
            '</div></body>',
            ' xmlns:m="' + METADATA_NS + '" xmlns:i="' + ITUNES_NS + '" i:timing="Word"'
            ' xmlns:u="urn:PRIVATE_URI" xmlns:alias="urn:PRIVATE_URI" xmlns:v="urn:UNUSED_URI"'))
        assert result["elements"] == 9 and result["max_depth"] == 6 and result["timing"] == "Word"
        assert result["namespaces"] == {"ttml": 1, "metadata": 1, "itunes": 1, "xml": 1, "other": 2}
        assert result["attributes"] == {"begin": 2, "end": 2, "dur": 2, "timeContainer": 1,
                                        "metadata_role": 1, "metadata_agent": 1, "xml_space": 1}
        assert result["roles"] == {"x-bg": 1, "x-translation": 0, "x-roman": 0, "other": 0}
        assert result["lines"] == 1 and result["main_lines"] == 0
        assert result["timed_spans"] == 0 and result["nested_timed_spans"] == 1
        assert result["timed_divs"] == 1

    with case("time_forms"):
        for value, expected in (("0", 0), ("1s", 1000), ("1.2", 1200), ("1.23s", 1230),
                                ("1.234", 1234), ("0001.234s", 1234)):
            assert classify_time(value) == ("seconds", expected)
        for value, expected in (("1:02", 62000), ("01:02.345", 62345),
                                ("01:02:03.004", 3723004), ("100:00:00", 360000000)):
            assert classify_time(value) == ("clock", expected)
        for value in ("", " 1", "1 ", "-1", "+1", ".1", "1.", "1.2345s", "1ms",
                      "60:00", "00:60", "1:2", "1:02:03", "00:00.0000", "PRIVATE_TIME"):
            assert classify_time(value) == ("other", None)
        assert classify_time("9" * 5000) == ("seconds", None)
        assert classify_time("9" * 5000 + ":00:00") == ("clock", None)
        assert classify_time("9223372036854775.807") == ("seconds", 9223372036854775807)
        assert classify_time("9223372036854775.808") == ("seconds", None)

    with case("timing_counts"):
        result = ttml_summary(xml(
            '<p><span begin="1" end="2"><span begin="1.25s" dur="0.1s"/></span></p>'
            '<p begin="00:01.500" end="00:00:03"><span end="02:00"/></p><div dur="1s"/>'))
        assert result["lines"] == 2 and result["timed_lines"] == 1 and result["p_without_begin"] == 1
        assert result["timed_spans"] == 2 and result["spans_with_begin_and_end"] == 1
        assert result["nested_timed_spans"] == 1 and result["timed_divs"] == 1
        assert result["time_formats"] == {
            "begin": {"seconds": 2, "clock": 1, "other": 0},
            "end": {"seconds": 1, "clock": 2, "other": 0},
            "dur": {"seconds": 2, "clock": 0, "other": 0},
        }

    with case("main_exclusions"):
        result = ttml_summary(xml(
            '<head><p begin="0" end="20"/></head><body>'
            '<div m:role="x-bg"><p begin="0" end="20"/></div>'
            '<p m:role="x-translation" begin="0" end="20"/>'
            '<div m:role="x-roman"><div><p begin="0" end="20"/></div></div>'
            '<p begin="1" end="2"/><p begin="2" end="3"/>'
            '<p m:role="PRIVATE_UNKNOWN_ROLE" begin="4" end="5"/></body>',
            ' xmlns:m="' + METADATA_NS + '"'))
        assert result["lines"] == 7 and result["main_lines"] == 3
        assert result["main_start_indeterminate"] == 0 and result["main_interval_indeterminate"] == 0
        assert result["main_simultaneous_start_pairs"] == 0 and result["main_known_overlap_pairs"] == 0
        assert result["roles"] == {"x-bg": 1, "x-translation": 1, "x-roman": 1, "other": 1}

    with case("main_overlap_pairs"):
        result = ttml_summary(xml(''.join('<p begin="' + start + '" end="' + end + '"/>'
                                         for start, end in (("4", "5"), ("1", "10"), ("2", "3"),
                                                            ("1", "2"), ("10", "11"), ("1", "4")))))
        assert result["main_lines"] == 6
        assert result["main_simultaneous_start_pairs"] == 3 and result["main_known_overlap_pairs"] == 6
        assert result["main_start_indeterminate"] == 0 and result["main_interval_indeterminate"] == 0

    with case("main_indeterminate_times"):
        result = ttml_summary(xml(
            '<p end="2"/><p begin="PRIVATE_TIME" end="3"/><p begin="1" end="PRIVATE_TIME"/>'
            '<p begin="2"/><p begin="3" end="2"/><p begin="4" end="4"/>'
            '<p begin="5" end="6"/><p begin="1.0000" end="7"/>'
            '<p begin="9999999999999999999999" end="8"/>'))
        assert result["main_lines"] == 9
        assert result["main_start_indeterminate"] == 4 and result["main_interval_indeterminate"] == 8
        assert result["main_simultaneous_start_pairs"] == 0 and result["main_known_overlap_pairs"] == 0
        assert result["time_formats"]["begin"] == {"seconds": 6, "clock": 0, "other": 2}
        assert result["time_formats"]["end"] == {"seconds": 7, "clock": 0, "other": 1}

    with case("main_unresolved_context"):
        result = ttml_summary(xml(
            '<div begin="1"><p begin="1" end="2"/></div>'
            '<div timeContainer="seq"><p begin="1" end="2"/></div>'
            '<p begin="1" end="2" dur="1"/><p begin="1" end="2" timeContainer="par"/>'
            '<div><p begin="1" end="2"/></div>'))
        assert result["main_lines"] == 5
        assert result["main_start_indeterminate"] == 3 and result["main_interval_indeterminate"] == 4
        assert result["main_simultaneous_start_pairs"] == 1 and result["main_known_overlap_pairs"] == 0

    with case("xml_depth_boundary"):
        assert ttml_summary(xml())["max_depth"] == 1
        result = ttml_summary(xml('<head>' + '<x>' * 62 + '</x>' * 62 + '</head>'))
        assert result["max_depth"] == 64 and result["elements"] == 64
        xml_error(xml('<head>' + '<x>' * 63 + '</x>' * 63 + '</head>'), "ttml_depth_limit")

    with case("xml_element_boundary"):
        result = ttml_summary(xml('<head>' + '<x/>' * (MAX_XML_ELEMENTS - 2) + '</head>'))
        assert result["elements"] == 25000 and result["max_depth"] == 3
        xml_error(xml('<head>' + '<x/>' * (MAX_XML_ELEMENTS - 1) + '</head>'), "ttml_element_limit")

    with case("xml_size_boundary"):
        remaining = MAX_TTML - len(xml().encode("utf-8"))
        padding = "é" * (remaining // 2) + "x" * (remaining % 2)
        assert ttml_summary(xml(padding))["bytes"] == MAX_TTML
        xml_error(xml(padding + "x"), "invalid_ttml_size")
        xml_error(None, "invalid_ttml_size")

    with case("xml_fixed_errors"):
        xml_error('<!ENTITY secret "PRIVATE_ENTITY">' + xml(), "ttml_declarations_rejected")
        xml_error('<!doctype tt SYSTEM "PRIVATE_DTD_URI">' + xml(), "ttml_declarations_rejected")
        xml_error(xml('<PRIVATE_MALFORMED>'), "invalid_ttml_xml")
        xml_error('<tt xmlns="urn:PRIVATE_ROOT"/>', "unexpected_ttml_root")
        assert summarize_response({"ttml": xml('<PRIVATE_MALFORMED>')})[0]["error"] == "invalid_ttml_xml"

    with case("secret_output"):
        secrets = ("PRIVATE_JSON_KEY_SECRET", "PRIVATE_NAMESPACE_SECRET", "PRIVATE_ROLE_SECRET",
                   "PRIVATE_AGENT_SECRET", "PRIVATE_ID_SECRET", "PRIVATE_BEGIN_SECRET", "PRIVATE_END_SECRET",
                   "PRIVATE_DUR_SECRET", "PRIVATE_CONTAINER_SECRET", "PRIVATE_SPACE_SECRET", "PRIVATE_TIMING_SECRET",
                   "PRIVATE_ATTRIBUTE_SECRET", "PRIVATE_VALUE_SECRET", "PRIVATE_ELEMENT_SECRET", "PRIVATE_TEXT_SECRET",
                   "PRIVATE_TOKEN_SECRET", "PRIVATE_BEARER_SECRET", "PRIVATE_ERROR_SECRET")
        secret_xml = xml(
            '<head><m:agent xml:id="PRIVATE_ID_SECRET"/></head><body xml:space="PRIVATE_SPACE_SECRET">'
            '<p m:role="PRIVATE_ROLE_SECRET" m:agent="PRIVATE_AGENT_SECRET" begin="PRIVATE_BEGIN_SECRET" '
            'end="PRIVATE_END_SECRET" dur="PRIVATE_DUR_SECRET" timeContainer="PRIVATE_CONTAINER_SECRET" '
            'PRIVATE_ATTRIBUTE_SECRET="PRIVATE_VALUE_SECRET">PRIVATE_TEXT_SECRET'
            '<u:PRIVATE_ELEMENT_SECRET/></p></body>',
            ' xmlns:m="' + METADATA_NS + '" xmlns:i="' + ITUNES_NS + '"'
            ' xmlns:u="urn:PRIVATE_NAMESPACE_SECRET" i:timing="PRIVATE_TIMING_SECRET"')
        payload = {"PRIVATE_JSON_KEY_SECRET": {"ttml": secret_xml},
                   "included": [{"ttml": secret_xml}, {"ttml": xml('<PRIVATE_ERROR_SECRET>')}]}
        claims = base64.urlsafe_b64encode(json.dumps({"exp": time.time() + 3600}).encode()).decode().rstrip("=")
        bearer = 'eyJhbGciOiJFUzI1NiJ9.' + claims + '.PRIVATE_BEARER_SECRET'
        calls = []

        def fake_read(url, headers=None, limit=MAX_API):
            calls.append(url)
            if url.endswith("/us/browse"):
                return 200, bearer.encode()
            assert headers["Authorization"] == "Bearer " + bearer
            assert headers["media-user-token"] == "PRIVATE_TOKEN_SECRET"
            if url.endswith("/v1/me/storefront"):
                return 200, b'{"data":[{"id":"jp"}]}'
            return 200, json.dumps(payload).encode()

        original = globals()["read_url"]
        try:
            globals()["read_url"] = fake_read
            output = io.StringIO()
            with redirect_stdout(output):
                emit("synthetic_structure", ttml=summarize_response(payload))
                emit("synthetic_limits", ttml=summarize_response({"ttml": xml('<x/>' * MAX_XML_ELEMENTS)}))
                assert probe("1537460612", "jp", "PRIVATE_TOKEN_SECRET") == 0
            log = output.getvalue()
            assert all(secret not in log for secret in secrets) and bearer not in log
            events = [json.loads(line) for line in log.splitlines()]
            api_events = [event for event in events if event["event"] == "api"]
            assert len(calls) == 5 and len(api_events) == 3
            assert all(event["status"] == 200 and event["bytes"] == len(json.dumps(payload).encode())
                       for event in api_events)
            assert all(len(event["ttml"]) == 2 and event["ttml"][0]["occurrences"] == 2 for event in api_events)
            assert events[-1]["ttml_documents"] == 3
            assert events[0]["ttml"][0]["paths"] == [["<other>", "ttml"], ["included", "[]", "ttml"]]
            assert events[0]["ttml"][0]["roles"] == {"x-bg": 0, "x-translation": 0, "x-roman": 0, "other": 1}
            assert events[1]["ttml"][0]["error"] == "ttml_element_limit"
        finally:
            globals()["read_url"] = original

    emit("contract_self_test", passed=True, cases=cases, failures=0)


def self_test():
    # No real lyrics, credentials, network calls or filesystem writes.
    import io
    from contextlib import redirect_stdout
    sample = ('<tt xmlns="http://www.w3.org/ns/ttml" '
              'xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">'
              '<body><div><p begin="1.0" end="3.0"><span begin="1.0" end="2.0">'
              'PRIVATE_SYNTHETIC_TEXT</span><span begin="2.0" end="3.0">'
              'SECOND_SYNTHETIC_WORD</span></p></div></body></tt>')
    summaries = summarize_response({"data": [{"attributes": {"ttml": sample}}]})
    assert summaries[0]["lines"] == 1 and summaries[0]["timed_spans"] == 2
    assert summaries[0]["spans_with_begin_and_end"] == 2
    assert "SYNTHETIC" not in json.dumps(summaries)
    assert summarize_response({"secret": "PRIVATE_TOKEN", "errors": []}) == []
    assert len(summarize_response([{"ttml": sample}, {"ttml": sample}])) == 1
    try:
        ttml_summary('<!DOCTYPE tt [<!ENTITY data "x">]><tt/>')
        raise AssertionError("DTD accepted")
    except ProbeError as error:
        assert str(error) == "ttml_declarations_rejected"
    assert NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.invalid") is None
    original = globals()["read_url"]
    try:
        calls = []
        def fake_read(url, headers=None, limit=MAX_API):
            calls.append(url)
            if url.endswith("/us/browse"):
                claims = base64.urlsafe_b64encode(json.dumps({"exp": time.time() + 3600}).encode()).decode().rstrip("=")
                return 200, ('eyJhbGciOiJFUzI1NiJ9.' + claims + '.SIGNATURE').encode()
            if url.endswith("/v1/me/storefront"):
                assert headers.get("media-user-token") == "PRIVATE_TOKEN"
                return 200, b'{"data":[{"id":"jp"}]}'
            return 200, json.dumps({"data": [{"attributes": {"ttml": sample}}]}).encode()
        globals()["read_url"] = fake_read
        output = io.StringIO()
        with redirect_stdout(output):
            assert probe("1537460612", "jp", "PRIVATE_TOKEN") == 0
        assert "PRIVATE_TOKEN" not in output.getvalue()
        assert "SYNTHETIC" not in output.getvalue()
        assert len(calls) == 5
    finally:
        globals()["read_url"] = original
    contract_self_test()
    emit("self_test", passed=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--public-only", action="store_true")
    mode.add_argument("--interactive", action="store_true")
    mode.add_argument("--self-test", action="store_true")
    parser.add_argument("--song-id", default="1537460612")
    parser.add_argument("--storefront", default="jp")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]{1,20}", args.song_id) or not re.fullmatch(r"[a-z]{2}", args.storefront):
        parser.error("invalid song ID or storefront")
    if args.self_test:
        self_test()
        return 0
    user_token = None
    if args.interactive:
        user_token = request_user_token()
    return probe(args.song_id, args.storefront, user_token)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ProbeError as error:
        emit("error", code=str(error))
        sys.exit(1)
    except (KeyboardInterrupt, EOFError):
        emit("error", code="cancelled")
        sys.exit(1)
    except Exception as error:
        # Never print exception messages or tracebacks that could contain secrets.
        emit("error", code="unexpected_failure", error_type=type(error).__name__)
        sys.exit(1)
