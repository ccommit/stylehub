"""측정 전에 구매자 1,000명을 미리 로그인시켜 세션 쿠키를 파일로 남긴다. 로그인(BCrypt) 비용이 측정 구간에 섞이지 않게 하려는 것."""
import json
import sys
from concurrent.futures import ThreadPoolExecutor

import requests

host, out, count = sys.argv[1], sys.argv[2], int(sys.argv[3])


def login(i):
    s = requests.Session()
    r = s.post(f"{host}/api/v1/users/login",
               json={"email": f"perf_buyer_{i}@perf.test", "password": "Test1234!"}, timeout=30)
    if r.status_code != 200:
        return None
    return "; ".join(f"{c.name}={c.value}" for c in s.cookies)


with ThreadPoolExecutor(32) as ex:
    cookies = list(ex.map(login, range(1, count + 1)))

ok = [c for c in cookies if c]
json.dump(ok, open(out, "w"))
print(f"logged_in={len(ok)}/{count}")
