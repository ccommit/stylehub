import os
import tempfile
import unittest
from datetime import datetime, timezone
from itertools import count
from pathlib import Path

from sim import data
from sim.api import ApiError
from setup.provision import Config, acquire_lock, resolve_state_dir, run, save_state


class FakeServer:
    # 가입·승인·상품·쿠폰·배송지를 메모리에 두는 가짜 StyleHub

    def __init__(self):
        self.ids = count(1)
        self.users = {}
        self.passwords = {}
        self.store_status = {}
        self.products = {}
        self.addresses = {}
        self.calls = []
        self.register_calls = 0
        self.fail_on_register_call = None
        self.conflict_code_override = None
        self.fail_coupon_names = set()


class FakeApi:

    def __init__(self, server):
        self.server = server
        self.user_id = None

    def _call(self, name):
        self.server.calls.append(name)

    def sign_up(self, user, password):
        self._call("sign_up")
        if user["email"] in self.server.users:
            raise ApiError(409, self.server.conflict_code_override or "U001", "")
        user_id = next(self.server.ids)
        self.server.users[user["email"]] = user_id
        self.server.passwords[user_id] = password

    def sign_up_store(self, store, password):
        self._call("sign_up_store")
        if store["email"] in self.server.users:
            raise ApiError(409, self.server.conflict_code_override or "U001", "")
        user_id = next(self.server.ids)
        self.server.users[store["email"]] = user_id
        self.server.passwords[user_id] = password
        self.server.store_status[user_id] = "PENDING"

    def login(self, email, password):
        user_id = self.server.users[email]
        if self.server.passwords[user_id] != password:
            raise ApiError(401, "U005", "")
        self.user_id = user_id
        return {"userId": user_id}

    def get_store(self, store_id):
        return {"status": self.server.store_status[store_id]}

    def approve_store(self, store_id):
        self._call("approve_store")
        self.server.store_status[store_id] = "APPROVED"

    def register_product(self, store_id, product):
        self._call("register_product")
        if self.server.store_status.get(store_id) != "APPROVED":
            raise ApiError(403, "P001", "")
        self.server.register_calls += 1
        product_id = next(self.server.ids)
        options = [{"productOptionId": next(self.server.ids)} for _ in product["options"]]
        self.server.products.setdefault(store_id, []).append(
            {"productId": product_id, "name": product["name"], "options": options})
        if self.server.register_calls == self.server.fail_on_register_call:
            # 서버엔 만들어졌지만 응답이 유실된 상황(딱 한 번만 재현)
            self.server.fail_on_register_call = None
            raise TimeoutError("응답을 받지 못함")
        return {"productId": product_id, "options": options}

    def get_product(self, product_id):
        for items in self.server.products.values():
            for item in items:
                if item["productId"] == product_id:
                    return {"options": item["options"]}
        raise ApiError(404, "PR001", "")

    def store_products(self, store_id, cursor=None):
        items = [{"productId": item["productId"], "name": item["name"]}
                 for item in self.server.products.get(store_id, [])]
        return {"items": items, "nextCursor": None, "hasNext": False}

    def create_coupon_event(self, store_id, event):
        self._call("create_coupon_event")
        if self.server.store_status.get(store_id) != "APPROVED":
            raise ApiError(403, "P001", "")
        if event["name"] in self.server.fail_coupon_names:
            raise ApiError(400, "CP008", "")
        return {"couponEventId": next(self.server.ids)}

    def addresses(self):
        return self.server.addresses.get(self.user_id, [])

    def add_address(self, address):
        self._call("add_address")
        created = {"addressId": next(self.server.ids)}
        self.server.addresses.setdefault(self.user_id, []).append(created)
        return created


class ProvisionTest(unittest.TestCase):

    def setUp(self):
        self.server = FakeServer()
        # 관리자는 사람이 미리 만들어 둔 계정이다(Task 7 Step 2)
        self.server.users["admin@example.com"] = 0
        self.server.passwords[0] = "admin-password"
        self.cfg = Config(stores=2, products_per_store=2, buyers=3, coupon_days=2, signup_rate=1000.0,
                          server_tz=timezone.utc, admin_email="admin@example.com",
                          now_kst=datetime(2026, 9, 19, 4, 0, tzinfo=data.KST))
        self.state = {"password": data.account_password(1), "stores": {}, "buyers": {}}
        self.asked = []

    def run_once(self):
        return run(self.cfg, lambda: FakeApi(self.server), self.state, lambda: None,
                   lambda: self.asked.append(True) or "admin-password")

    def test_first_run_creates_everything(self):
        summary = self.run_once()
        self.assertEqual(summary, {"stores_approved": 2, "products": 4, "coupon_events": 4,
                                    "coupon_pending": 0, "buyers_ready": 3})
        self.assertEqual(set(self.server.store_status.values()), {"APPROVED"})
        self.assertEqual(len(self.asked), 1)

    def test_second_run_creates_nothing_and_does_not_ask_admin_password(self):
        self.run_once()
        self.server.calls.clear()
        self.asked.clear()
        summary = self.run_once()
        self.assertEqual(summary, {"stores_approved": 2, "products": 4, "coupon_events": 4,
                                    "coupon_pending": 0, "buyers_ready": 3})
        self.assertEqual(self.server.calls, [])
        self.assertEqual(self.asked, [])

    def test_rerun_adopts_product_created_before_crash(self):
        # 두 번째 register_product 호출에서 서버는 상품을 만들었지만 응답이 유실됐다고 가정한다
        self.server.fail_on_register_call = 2
        with self.assertRaises(TimeoutError):
            self.run_once()
        summary = self.run_once()
        self.assertEqual(summary["products"], 4)
        total_on_server = sum(len(items) for items in self.server.products.values())
        self.assertEqual(total_on_server, 4)

    def test_pending_coupon_is_not_posted_again(self):
        self.run_once()
        name = next(iter(self.state["stores"]["1"]["coupons"]))
        self.state["stores"]["1"]["coupons"][name] = "pending"
        self.server.calls.clear()
        summary = self.run_once()
        self.assertNotIn("create_coupon_event", self.server.calls)
        self.assertEqual(summary["coupon_pending"], 1)

    def test_signup_conflict_logs_in_existing_account(self):
        info = data.buyer(1)
        FakeApi(self.server).sign_up(info, self.state["password"])
        summary = self.run_once()
        self.assertEqual(summary["buyers_ready"], 3)

    def test_wrong_password_after_conflict_exits(self):
        info = data.store(1)
        FakeApi(self.server).sign_up_store(info, "Different000!x")
        with self.assertRaises(SystemExit):
            self.run_once()

    def test_sign_up_conflict_with_unhandled_code_reraises(self):
        # U002(이름 중복) 등은 "이미 가입됨"이 아니라 진짜 문제이므로 그대로 올라와야 한다
        info = data.store(1)
        FakeApi(self.server).sign_up_store(info, "Different000!x")
        self.server.conflict_code_override = "U002"
        with self.assertRaises(ApiError):
            self.run_once()

    def test_coupon_rejection_clears_pending_marker_and_raises(self):
        schedule = data.coupon_events(data.first_coupon_day(self.cfg.now_kst), self.cfg.coupon_days, self.cfg.server_tz)
        name = schedule[0]["name"]
        self.server.fail_coupon_names = {name}
        with self.assertRaises(ApiError):
            self.run_once()
        self.assertNotIn(name, self.state["stores"]["1"]["coupons"])


class AcquireLockTest(unittest.TestCase):

    def test_second_lock_on_same_path_raises_system_exit(self):
        # flock 은 open 된 파일 기술자 단위로 걸리므로, 같은 프로세스라도 서로 다른 open() 이면 두 번째가 막힌다
        with tempfile.TemporaryDirectory() as state_dir:
            path = Path(state_dir) / "provision-example.com_8080.json"
            first = acquire_lock(path)
            try:
                with self.assertRaises(SystemExit):
                    acquire_lock(path)
            finally:
                first.close()


class SaveStatePermissionsTest(unittest.TestCase):

    def test_save_state_creates_directory_0700_and_file_0600(self):
        with tempfile.TemporaryDirectory() as parent:
            path = Path(parent) / "fresh-state-dir" / "provision-example.com_8080.json"
            save_state(path, {"password": "x", "stores": {}, "buyers": {}})
            self.assertEqual(os.stat(path.parent).st_mode & 0o777, 0o700)
            self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)


class ResolveStateDirTest(unittest.TestCase):

    def test_empty_value_uses_home_default(self):
        self.assertEqual(resolve_state_dir(""), Path.home() / ".stylehub-sim")
        self.assertEqual(resolve_state_dir(None), Path.home() / ".stylehub-sim")

    def test_relative_value_resolves_to_absolute_path(self):
        with tempfile.TemporaryDirectory() as unrelated_repo_root:
            resolved = resolve_state_dir("some/relative/dir", repo_root=unrelated_repo_root)
            self.assertTrue(resolved.is_absolute())

    def test_path_inside_repo_root_is_refused(self):
        with tempfile.TemporaryDirectory() as repo_root:
            inside = Path(repo_root) / "traffic-sim" / ".state"
            with self.assertRaises(SystemExit):
                resolve_state_dir(str(inside), repo_root=repo_root)
            with self.assertRaises(SystemExit):
                resolve_state_dir(repo_root, repo_root=repo_root)


if __name__ == "__main__":
    unittest.main()
