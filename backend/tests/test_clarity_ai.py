from __future__ import annotations

import pytest

from services.clarity_ai import ROADMAP_MARKER, PROMPT_TEMPLATE, SectionGate, compute_kpis, normalize, sanitize_path, top_targets


# ---------------------------------------------------------------- normalize
def test_normalize_keeps_only_whitelisted_metrics_and_fields():
    raw = [
        {"metricName": "Traffic", "information": [
            {"totalSessionCount": "120", "distinctUserCount": "90", "Url": "/home", "email": "a@b.com"}]},
        {"metricName": "RageClickCount", "information": [
            {"sessionsCount": "4", "pagesViews": "6", "subTotal": "11", "Url": "/home", "ip": "1.2.3.4"}]},
        {"metricName": "PopularPages", "information": [{"url": "/secret", "visitsCount": "5"}]},
    ]
    out = normalize(raw, "URL")
    assert set(out) == {"Traffic", "RageClickCount"}
    assert out["Traffic"] == {"/home": {"sessions": 120, "users": 90}}
    assert out["RageClickCount"] == {"/home": {"sessions": 4, "pageviews": 6, "total": 11}}


def test_normalize_aggregates_duplicate_dimension_values_and_handles_quirks():
    raw = [
        {"metricName": "DeadClickCount", "information": [
            {"sessionsCount": "1.0", "pageViews": "2", "subTotal": "3", "url": "/a"},
            {"sessionsCount": 2, "pagesViews": "x", "subTotal": None, "URL": "/a"},
            "not-a-dict",
            {"sessionsCount": "7", "subTotal": "7"},  # no dimension -> "(all)"
        ]},
        {"metricName": "Traffic", "information": [{"totalSessionCount": "5", "distantUserCount": "4", "Url": ""}]},
        {"metricName": "QuickbackClick", "information": None},
    ]
    out = normalize(raw, "URL")
    assert out["DeadClickCount"]["/a"] == {"sessions": 3, "pageviews": 2, "total": 3}
    assert out["DeadClickCount"]["(all)"] == {"sessions": 7, "pageviews": 0, "total": 7}
    assert out["Traffic"] == {"(all)": {"sessions": 5, "users": 4}}
    assert out["QuickbackClick"] == {}


def test_normalize_device_dimension():
    raw = [{"metricName": "Traffic", "information": [{"totalSessionCount": "9", "Device": "Mobile"}]}]
    assert normalize(raw, "Device") == {"Traffic": {"Mobile": {"sessions": 9, "users": 0}}}


# ---------------------------------------------------------------- compute_kpis
def test_compute_kpis_perfect_health_when_no_friction():
    k = compute_kpis({"Traffic": {"/": {"sessions": 1000, "users": 1}}})
    assert k["health_score"] == 100
    assert k["total_sessions"] == 1000
    assert k["rage_session_pct"] == 0.0


def test_compute_kpis_rates_and_penalties():
    norm = {
        "Traffic": {"/a": {"sessions": 600}, "/b": {"sessions": 400}},
        "RageClickCount": {"/a": {"sessions": 50, "total": 80}},  # 5% -> half of 30 = 15
        "DeadClickCount": {"/b": {"sessions": 200, "total": 300}},  # 20% -> saturates at 25
    }
    k = compute_kpis(norm)
    assert k["rage_session_pct"] == 5.0
    assert k["rage_click_count"] == 80
    assert k["dead_click_session_pct"] == 20.0
    assert k["dead_click_count"] == 300
    assert k["health_score"] == 60


def test_compute_kpis_falls_back_to_metric_sessions_without_traffic():
    k = compute_kpis({"RageClickCount": {"/": {"sessions": 10, "total": 10}}})
    assert k["total_sessions"] == 10
    assert k["rage_session_pct"] == 100.0
    assert k["health_score"] == 70


def test_compute_kpis_empty():
    k = compute_kpis({})
    assert k["total_sessions"] == 0
    assert k["health_score"] == 100


def test_compute_kpis_floors_at_zero():
    norm = {m: {"/": {"sessions": 100, "total": 100}} for m in
            ("Traffic", "RageClickCount", "DeadClickCount", "QuickbackClick",
             "ExcessiveScroll", "ErrorClickCount", "ScriptErrorCount")}
    assert compute_kpis(norm)["health_score"] == 0


# ---------------------------------------------------------------- top_targets
def test_top_targets_ranks_by_frustration_and_limits():
    norm = {
        "Traffic": {"/a": {"sessions": 10}, "/b": {"sessions": 20}, "/c": {"sessions": 5}, "(all)": {"sessions": 99}},
        "RageClickCount": {"/a": {"total": 1}, "/b": {"total": 5}, "(all)": {"total": 100}},
        "DeadClickCount": {"/a": {"total": 10}},
    }
    out = top_targets(norm, 2)
    assert [t["element"] for t in out] == ["/a", "/b"]  # 3*1+2*10=23 > 3*5=15; /c has score 0
    assert out[0]["frustration_score"] == 23.0
    assert out[0]["sessions"] == 10
    assert out[0]["target_type"] == "page_path"
    assert len(top_targets(norm, 1)) == 1


def test_top_targets_merges_paths_that_sanitize_identically():
    norm = {
        "RageClickCount": {
            "https://x.com/orders/12345?token=abc": {"total": 2},
            "https://x.com/orders/67890": {"total": 3},
        },
    }
    out = top_targets(norm, 10)
    assert len(out) == 1
    assert out[0]["element"] == "/orders/:id"
    assert out[0]["rage_clicks"] == 5


# ---------------------------------------------------------------- sanitize_path
@pytest.mark.parametrize("url,expected", [
    ("https://shop.example.com/checkout?email=a@b.com#step2", "/checkout"),
    ("shop.example.com/cart", "/cart"),
    ("/users/123456/profile", "/users/:id/profile"),
    ("/u/550e8400-e29b-41d4-a716-446655440000", "/u/:id"),
    ("/files/deadbeefdeadbeef00", "/files/:id"),
    ("/invite/john.doe@example.com", "/invite/:id"),
    ("/reset/AbCdEfGhIjKlMnOpQrStUvWxYz", "/reset/:id"),
    ("/page/12", "/page/12"),
    ("https://example.com", "/"),
    ("/", "/"),
])
def test_sanitize_path(url, expected):
    assert sanitize_path(url) == expected


def test_sanitize_path_truncates_long_segments():
    seg = "a-very-long-but-human-readable-segment-name-here"  # contains dashes but >24 chars -> id-like
    assert sanitize_path(f"/{seg}") == "/:id"
    readable = "Some Long Segment With Spaces In It Ok Yes"
    assert sanitize_path(f"/{readable}") == "/" + readable[:40]


# ---------------------------------------------------------------- SectionGate
def test_marker_matches_prompt_header():
    assert ROADMAP_MARKER == "### \U0001F680"
    assert ROADMAP_MARKER + " Strategic Next-Sprint Product Roadmap" in PROMPT_TEMPLATE


def test_section_gate_disabled_is_passthrough():
    g = SectionGate(enabled=False)
    assert g.feed(f"a {ROADMAP_MARKER} b") == f"a {ROADMAP_MARKER} b"
    assert not g.blocked
    assert g.flush() == ""


def test_section_gate_blocks_marker_split_across_chunks():
    g = SectionGate(enabled=True)
    text = "### hotfix\n- x\n" + ROADMAP_MARKER + " Roadmap\n- secret\n"
    out = ""
    for ch in text:  # worst case: one character per chunk
        out += g.feed(ch)
        if g.blocked:
            break
    out += g.flush()
    assert g.blocked
    assert out == "### hotfix\n- x\n"
    assert g.feed("more") == ""


def test_section_gate_without_marker_emits_everything_after_flush():
    g = SectionGate(enabled=True)
    out = g.feed("hello ") + g.feed("world")
    out += g.flush()
    assert out == "hello world"
    assert not g.blocked
