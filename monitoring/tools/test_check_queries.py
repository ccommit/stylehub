import io
import socket
import unittest
import urllib.error

import check_queries
from check_queries import extract_prometheus_queries


class ExtractPrometheusQueriesTest(unittest.TestCase):

    def test_collects_prometheus_targets_and_skips_other_datasources(self):
        dashboard = {"panels": [
            {"title": "p95", "datasource": {"type": "prometheus", "uid": "prometheus"},
             "targets": [{"refId": "A", "expr": "up"}, {"refId": "B", "expr": "node_load1"}]},
            {"title": "크레딧", "datasource": {"type": "cloudwatch", "uid": "cloudwatch"},
             "targets": [{"refId": "A", "metricName": "CPUCreditBalance"}]},
        ]}

        self.assertEqual(extract_prometheus_queries(dashboard), [("p95", "up"), ("p95", "node_load1")])

    def test_target_datasource_overrides_panel_datasource(self):
        dashboard = {"panels": [
            {"title": "섞임", "datasource": {"type": "cloudwatch", "uid": "cloudwatch"},
             "targets": [{"refId": "A", "expr": "up", "datasource": {"type": "prometheus", "uid": "prometheus"}}]},
        ]}

        self.assertEqual(extract_prometheus_queries(dashboard), [("섞임", "up")])

    def test_collects_queries_from_panels_nested_in_a_collapsed_row(self):
        dashboard = {"panels": [
            {"title": "접힌 행", "type": "row", "collapsed": True, "panels": [
                {"title": "안쪽 패널", "datasource": {"type": "prometheus", "uid": "prometheus"},
                 "targets": [{"refId": "A", "expr": "up"}]},
            ]},
        ]}

        self.assertEqual(extract_prometheus_queries(dashboard), [("안쪽 패널", "up")])

    def test_collects_queries_from_panels_nested_two_rows_deep(self):
        dashboard = {"panels": [
            {"title": "바깥 행", "type": "row", "collapsed": True, "panels": [
                {"title": "안쪽 행", "type": "row", "collapsed": True, "panels": [
                    {"title": "가장 안쪽", "datasource": {"type": "prometheus", "uid": "prometheus"},
                     "targets": [{"refId": "A", "expr": "node_load1"}]},
                ]},
            ]},
        ]}

        self.assertEqual(extract_prometheus_queries(dashboard), [("가장 안쪽", "node_load1")])

    def test_panel_level_string_datasource_is_skipped_without_crashing(self):
        dashboard = {"panels": [
            {"title": "옛 형식", "datasource": "Prometheus",
             "targets": [{"refId": "A", "expr": "up"}]},
        ]}

        self.assertEqual(extract_prometheus_queries(dashboard), [])

    def test_target_level_string_datasource_is_skipped_without_crashing(self):
        dashboard = {"panels": [
            {"title": "옛 타깃", "datasource": {"type": "prometheus", "uid": "prometheus"},
             "targets": [{"refId": "A", "expr": "up", "datasource": "Prometheus"}]},
        ]}

        self.assertEqual(extract_prometheus_queries(dashboard), [])


class RunQueryTest(unittest.TestCase):

    def test_connection_failure_returns_error_status_instead_of_crashing(self):
        original_url = check_queries.PROMETHEUS_URL
        check_queries.PROMETHEUS_URL = "http://127.0.0.1:1"
        try:
            status, _detail = check_queries.run_query("up")
        finally:
            check_queries.PROMETHEUS_URL = original_url

        self.assertEqual(status, "ERROR")

    def test_http_error_with_non_json_body_returns_error_with_status_code(self):
        def fake_urlopen(url, timeout):
            raise urllib.error.HTTPError(url, 500, "Internal Server Error", {}, io.BytesIO(b"not json"))

        original_urlopen = check_queries.urllib.request.urlopen
        check_queries.urllib.request.urlopen = fake_urlopen
        try:
            status, detail = check_queries.run_query("up")
        finally:
            check_queries.urllib.request.urlopen = original_urlopen

        self.assertEqual((status, detail), ("ERROR", "HTTP 500"))

    def test_timeout_returns_error_with_korean_message(self):
        def fake_urlopen(url, timeout):
            raise socket.timeout("timed out")

        original_urlopen = check_queries.urllib.request.urlopen
        check_queries.urllib.request.urlopen = fake_urlopen
        try:
            status, detail = check_queries.run_query("up")
        finally:
            check_queries.urllib.request.urlopen = original_urlopen

        self.assertEqual((status, detail), ("ERROR", "Prometheus 응답 시간 초과"))


if __name__ == "__main__":
    unittest.main()
