import re
import unittest
from datetime import date, datetime, timedelta, timezone

from sim import data

# 서버 ValidationPatterns 와 같은 규칙
NAME = re.compile(r"^[가-힣a-zA-Z0-9]+$")
PASSWORD = re.compile(r"^(?=.*[A-Za-z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,15}$")
PHONE = re.compile(r"^\d{10,11}$")
ZIP = re.compile(r"^\d{5}$")
VALID_PAIRS = {
    "SHOES": {"SNEAKERS", "DRESS_SHOES", "RUNNING_SHOES"},
    "TOP": {"JACKET", "SWEATSHIRT", "T_SHIRT"},
    "BOTTOM": {"DENIM_PANTS", "SKIRT", "SHORT_PANTS"},
    "ACCESSORY": {"NECKLACE", "RING", "GLASSES"},
}


class AccountDataTest(unittest.TestCase):

    def test_buyer_and_store_pass_server_validation(self):
        for index in (1, 42, 99999):
            buyer = data.buyer(index)
            self.assertRegex(buyer["name"], NAME)
            self.assertTrue(2 <= len(buyer["name"]) <= 10)
            self.assertTrue(buyer["email"].endswith("@sim.stylehub.test"))
        for index in (1, 5, 99):
            store = data.store(index)
            self.assertRegex(store["name"], NAME)
            self.assertTrue(store["storeName"].startswith("[SIM]"))
            self.assertTrue(2 <= len(store["storeName"]) <= 20)

    def test_password_matches_server_pattern(self):
        for token in (0, 123456, 999999):
            self.assertRegex(data.account_password(token), PASSWORD)

    def test_new_account_password_meets_server_pattern_and_varies(self):
        passwords = [data.new_account_password() for _ in range(50)]
        for password in passwords:
            self.assertRegex(password, PASSWORD)
            self.assertEqual(len(password), 14)
        self.assertGreater(len(set(passwords)), 1)

    def test_address_passes_server_validation(self):
        for index in (1, 2000, 99999):
            address = data.address(index)
            self.assertRegex(address["phone"], PHONE)
            self.assertRegex(address["zipCode"], ZIP)
            self.assertTrue(2 <= len(address["recipientName"]) <= 20)
            self.assertLessEqual(len(address["streetAddress"]), 40)


class ProductDataTest(unittest.TestCase):

    def test_products_use_valid_category_pairs_and_limits(self):
        for store_index in range(1, 6):
            for number in range(1, 41):
                product = data.product(store_index, number)
                self.assertIn(product["subCategory"], VALID_PAIRS[product["mainCategory"]])
                self.assertLessEqual(len(product["name"]), 20)
                self.assertGreater(product["price"], 0)
                self.assertTrue(product["options"])
                for option in product["options"]:
                    self.assertLessEqual(len(option["size"]), 10)
                    self.assertGreaterEqual(option["stockQuantity"], 0)


class CouponScheduleTest(unittest.TestCase):

    def test_opens_at_20_kst_expressed_in_server_utc(self):
        events = data.coupon_events(date(2026, 9, 19), 2, timezone.utc)
        self.assertEqual(events[0]["startedAt"], "2026-09-19T11:00:00")
        self.assertEqual(events[1]["startedAt"], "2026-09-20T11:00:00")
        for event in events:
            started = datetime.fromisoformat(event["startedAt"])
            expired = datetime.fromisoformat(event["expiredAt"])
            self.assertGreaterEqual(expired - started, timedelta(days=1))
            self.assertLessEqual(len(event["name"]), 20)

    def test_first_day_skips_today_when_opening_is_within_30_minutes(self):
        self.assertEqual(data.first_coupon_day(datetime(2026, 9, 19, 4, 0, tzinfo=data.KST)), date(2026, 9, 19))
        self.assertEqual(data.first_coupon_day(datetime(2026, 9, 19, 19, 45, tzinfo=data.KST)), date(2026, 9, 20))


if __name__ == "__main__":
    unittest.main()
