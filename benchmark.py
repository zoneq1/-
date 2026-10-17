import concurrent.futures
import urllib.request
import time
import csv
import sys

VOUCHER_ID = sys.argv[1] if len(sys.argv) > 1 else "12"
MAX_WORKERS = int(sys.argv[2]) if len(sys.argv) > 2 else 200
CSV_PATH = "src/test/jmeter/tokens.csv"

tokens = []
with open(CSV_PATH, "r", encoding="utf-8") as f:
    reader = csv.reader(f)
    next(reader)  # skip header
    for row in reader:
        if row:
            tokens.append(row[0])

url = f"http://localhost:8081/voucher-order/seckill/{VOUCHER_ID}"

def seckill(token):
    req = urllib.request.Request(url, method="POST")
    req.add_header("authorization", token)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8")
            return ("ok" if '"success":true' in body else "fail", body)
    except Exception as e:
        return ("error", str(e))

start = time.time()
with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_WORKERS) as ex:
    results = list(ex.map(seckill, tokens))
elapsed = time.time() - start

ok = sum(1 for r, _ in results if r == "ok")
fail = sum(1 for r, _ in results if r == "fail")
errors = [b for r, b in results if r == "error"]

print(f"总请求数: {len(tokens)}")
print(f"并发线程: {MAX_WORKERS}")
print(f"抢到(成功): {ok}")
print(f"未抢到(失败): {fail}")
print(f"异常: {len(errors)}")
if errors:
    print(f"异常样例: {errors[0][:120]}")
print(f"总耗时: {elapsed:.2f}s")
print(f"QPS: {len(tokens)/elapsed:.1f}")
print(f"平均响应: {elapsed/len(tokens)*1000:.1f}ms")
