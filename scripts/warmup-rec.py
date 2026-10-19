#!/usr/bin/env python3
"""推荐链路演示预热：登录测试账号，为前 N 个商品各上报一次浏览行为。

用途：演示环境初始时热度榜为空，热门路候选不足会走「兜底名单」。
预热后热度榜覆盖足够商品，热门召回与个性化召回都能正常展示。

用法：
    python scripts/warmup-rec.py          # 默认预热商品 1-50
    python scripts/warmup-rec.py 100      # 预热商品 1-100
"""
import datetime
import json
import sys
import urllib.request

BASE = 'http://127.0.0.1:8080'


def post(path, body, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['token'] = token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode('utf-8'), headers=headers)
    return json.loads(urllib.request.urlopen(req, timeout=10).read())


def main():
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 50
    token = post('/api/user/login', {'username': 'user', 'password': '123456'})['data']['token']
    now = datetime.datetime.now().astimezone().isoformat(timespec='seconds')
    ok = 0
    for pid in range(1, count + 1):
        try:
            r = post('/api/rec/behavior/report', {
                'itemId': pid,
                'eventType': 'view',
                'eventTime': now,
                'eventId': 'evt_warmup_%d_%d' % (int(datetime.datetime.now().timestamp()), pid),
                'duration': 3000,
            }, token)
            if r.get('code') == 200:
                ok += 1
        except Exception:
            pass
    print('预热完成：成功上报 %d / %d 条浏览行为' % (ok, count))


if __name__ == '__main__':
    main()
