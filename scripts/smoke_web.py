#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
社区便民服务预约系统 · Web 层冒烟脚本（Batch 5 自检工具）
=====================================================================
用途：应用启动后对"页面渲染 + 接口契约 + 三级鉴权"做一轮只读巡检，
      覆盖三类角色（管理员/师傅/居民）的 11 个 Thymeleaf 页面与核心 API，
      并通过越权用例验证 Security 的 401/403/重定向三条防线。

设计要点：
  1. 每个角色独立 CookieJar（等价于三个互不干扰的浏览器会话），
     避免"上一个角色的会话把下一个角色的 401 用例顶掉"；
  2. 只读：不写库、不投递 MQ，跑完数据与跑前一致，可反复执行；
  3. 页面用例除了 HTTP 200 还断言关键中文标记，能捕获
     "模板语法错误被 error 页吞掉"这类只有 200 没有内容的假通过；
  4. 退出码 0/1 供 CI 或 shell 串联使用（打印 FAIL 明细则返回 1）。

用法：
    python scripts/smoke_web.py                 # 默认 http://127.0.0.1:9092
    python scripts/smoke_web.py 9092            # 只给端口
    python scripts/smoke_web.py http://ip:port  # 给完整基地址
"""

import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar

# 测试账号（与 sql/community_init.sql 种子一致，明文密码统一 123456）
ACCOUNTS = {
    'admin': ('admin', '123456'),
    'worker': ('worker1', '123456'),
    'resident': ('resident1', '123456'),
}

# 页面用例：(路径, 必须出现的中文标记)
PAGES = {
    'admin': [
        ('/admin/dashboard', '运营看板'),
        ('/admin/dispatch', '派单中心'),
        ('/admin/orders', '订单管理'),
        ('/admin/catalog', '服务目录'),
        ('/admin/workers', '师傅管理'),
    ],
    'worker': [
        ('/worker/workbench', '我的工单'),
        ('/worker/profile', '服务档案'),
        ('/worker/evaluations', '我的评价'),
    ],
    'resident': [
        ('/resident/home', '服务大厅'),
        ('/resident/orders', '我的预约'),
    ],
}

# 接口用例：(路径, 断言函数名)
APIS = {
    'admin': [
        ('/api/auth/me', 'user'),
        ('/api/admin/dashboard/overview', 'dashboard'),
        ('/api/admin/worker/page?pageNum=1&pageSize=10', 'page'),
        ('/api/admin/user/page?pageNum=1&pageSize=10', 'page'),
        ('/api/admin/item/page?pageNum=1&pageSize=10', 'page'),
        ('/api/admin/category/page?pageNum=1&pageSize=10', 'page'),
    ],
    'worker': [
        ('/api/auth/me', 'user'),
        ('/api/common/orders?pageNum=1&pageSize=5', 'page'),
        ('/api/common/evaluations?pageNum=1&pageSize=5', 'page'),
        ('/api/worker/profile', 'profile'),
    ],
    'resident': [
        ('/api/auth/me', 'user'),
        ('/api/common/categories', 'list'),
        ('/api/common/items?pageNum=1&pageSize=6', 'page'),
        ('/api/common/items/hot', 'list'),
        ('/api/common/orders?pageNum=1&pageSize=5', 'page'),
    ],
}

# JSON 字段缺失时给出可读结论
REQUIRED_FIELDS = {
    'user': ['username', 'role', 'realName'],
    'page': ['records', 'total', 'pageNum', 'pages'],
    'list': [],
    'dashboard': ['kpi', 'trend', 'statusDistribution', 'categoryDistribution',
                  'workerRank', 'hotItems'],
    'profile': ['userId', 'skillTags', 'workStatus'],
}


class Session(object):
    """一个角色一个会话：CookieJar 隔离，自动保存登录态。"""

    def __init__(self, base):
        self.base = base
        self.jar = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))

    def request(self, method, path, payload=None):
        """发一次请求，返回 (HTTP 状态码, 响应文本, 最终 URL)。"""
        url = self.base + path
        data = None
        headers = {'X-Requested-With': 'XMLHttpRequest'}
        if payload is not None:
            data = json.dumps(payload).encode('utf-8')
            headers['Content-Type'] = 'application/json; charset=UTF-8'
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=20) as resp:
                body = resp.read().decode('utf-8', 'replace')
                return resp.status, body, resp.geturl()
        except urllib.error.HTTPError as e:
            body = e.read().decode('utf-8', 'replace')
            return e.code, body, url

    def probe(self, path):
        """GET 且不跟随重定向，返回 (状态码, Location 头)——用于验证越权被弹回。
        <p>刻意不带 X-Requested-With 头：浏览器地址栏导航不会带它，
        服务端据此把"页面越权"与"AJAX 越权"分流为 302 与 403 两种响应。</p>
        """
        req = urllib.request.Request(self.base + path)
        opener = urllib.request.build_opener(
            NoRedirect(), urllib.request.HTTPCookieProcessor(self.jar))
        try:
            with opener.open(req, timeout=20) as resp:
                return resp.status, resp.headers.get('Location', '')
        except urllib.error.HTTPError as e:
            return e.code, e.headers.get('Location', '')

    def login(self, username, password):
        """登录，返回业务 code（200 为成功）。"""
        status, body, _ = self.request(
            'POST', '/api/auth/login', {'username': username, 'password': password})
        if status != 200:
            return status
        try:
            return json.loads(body).get('code')
        except ValueError:
            return -1

    def get_json(self, path):
        """GET 并解包 Result，返回 (HTTP 状态, 业务 code, data, 原文)。"""
        status, body, _ = self.request('GET', path)
        try:
            obj = json.loads(body)
        except ValueError:
            return status, -1, None, body
        return status, obj.get('code'), obj.get('data'), body


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """禁用自动跟随：用于验证"页面越权被 302 弹回"而不是被静默放行。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def check_data(kind, data):
    """按用例类型校验 data 结构，返回 (ok, 说明)。"""
    if data is None:
        return False, 'data 为空'
    if kind == 'list':
        return (True, '共 %d 条' % len(data)) if isinstance(data, list) else (False, '不是数组')
    if not isinstance(data, dict):
        return False, '不是对象'
    missing = [f for f in REQUIRED_FIELDS.get(kind, []) if f not in data]
    if missing:
        return False, '缺字段 ' + ','.join(missing)
    if kind == 'page':
        return True, '共 %s 条 / %s 页' % (data.get('total'), data.get('pages'))
    if kind == 'dashboard':
        kpi = data.get('kpi') or {}
        return True, 'KPI %d 项 / 图表 5 张' % len(kpi)
    if kind == 'profile':
        return True, '技能=%s 状态=%s' % (data.get('skillTags'), data.get('workStatus'))
    return True, data.get('role') or data.get('username') or 'ok'


def main():
    """执行全量巡检并汇总结果。"""
    arg = sys.argv[1] if len(sys.argv) > 1 else '9092'
    base = arg if arg.startswith('http') else 'http://127.0.0.1:' + arg
    failures = []
    passed = 0

    def ok(msg):
        nonlocal passed
        passed += 1
        print('  PASS  ' + msg)

    def fail(msg):
        failures.append(msg)
        print('  FAIL  ' + msg)

    print('=' * 68)
    print('社区便民服务预约系统 · Web 冒烟巡检  base=%s' % base)
    print('=' * 68)

    # ---------- 0. 匿名访问 ----------
    print('[0] 匿名访问')
    anon = Session(base)
    status, body, _ = anon.request('GET', '/login')
    if status == 200 and '社区便民服务' in body:
        ok('GET /login 渲染登录页')
    else:
        fail('GET /login 状态=%s 无登录标记' % status)
    status, code, _, _ = anon.get_json('/api/auth/me')
    if status == 401 and code == 401:
        ok('GET /api/auth/me 未登录回 401 JSON')
    else:
        fail('GET /api/auth/me 未登录应为 401，实际 %s/%s' % (status, code))

    # ---------- 1. 三角色登录 + 页面 + 接口 ----------
    sessions = {}
    for role, (username, password) in ACCOUNTS.items():
        print('[%s] 账号 %s' % (role, username))
        sess = Session(base)
        code = sess.login(username, password)
        if code == 200:
            ok('POST /api/auth/login 登录成功')
        else:
            fail('登录失败 code=%s' % code)
            continue
        sessions[role] = sess

        for path, marker in PAGES[role]:
            status, body, final = sess.request('GET', path)
            if status == 200 and marker in body:
                ok('GET %s 渲染「%s」' % (path, marker))
            elif status == 200:
                fail('GET %s 返回 200 但缺少标记「%s」（模板可能未渲染）' % (path, marker))
            else:
                fail('GET %s 状态=%s' % (path, status))

        for path, kind in APIS[role]:
            status, bcode, data, raw = sess.get_json(path)
            if status == 200 and bcode == 200:
                good, detail = check_data(kind, data)
                if good:
                    ok('GET %s -> %s' % (path, detail))
                else:
                    fail('GET %s 结构异常：%s' % (path, detail))
            else:
                fail('GET %s 状态=%s code=%s %s' % (path, status, bcode, raw[:160]))

    # ---------- 2. 智能派单候选（Batch 4 算法出口） ----------
    print('[dispatch] 智能派单候选')
    admin = sessions.get('admin')
    if admin is None:
        fail('管理员会话缺失，跳过派单用例')
    else:
        _, _, page, _ = admin.get_json('/api/common/orders?pageNum=1&pageSize=1&orderStatus=0')
        orders = (page or {}).get('records') or []
        if not orders:
            fail('库内无待派单订单，无法验证候选接口（请先执行 sql/community_init.sql）')
        else:
            order_id = orders[0]['id']
            status, bcode, cands, raw = admin.get_json(
                '/api/admin/dispatch/candidates/%s' % order_id)
            if status != 200 or bcode != 200:
                fail('GET 候选 状态=%s code=%s %s' % (status, bcode, raw[:160]))
            elif not cands:
                fail('候选列表为空：待派单订单应有可评分师傅（检查技能词表与状态）')
            else:
                scored = [c for c in cands if c.get('matchScore') is not None]
                matched = [c for c in cands if c.get('skillMatched')]
                reasons = [c for c in cands if c.get('matchReason') or c.get('reason')]
                if len(scored) == len(cands):
                    ok('候选 %d 人全部含综合得分（最高 %s）'
                       % (len(cands), max(c['matchScore'] for c in cands)))
                else:
                    fail('存在未打分的候选：%d/%d' % (len(scored), len(cands)))
                if matched:
                    ok('技能匹配候选 %d 人' % len(matched))
                else:
                    fail('无技能匹配候选，算法技能维度可能失效')
                if reasons:
                    ok('候选含匹配理由 %d 条' % len(reasons))
                else:
                    fail('候选缺少匹配理由文本')

    # ---------- 3. 越权防线 ----------
    print('[security] 越权与角色边界')
    resident = sessions.get('resident')
    worker = sessions.get('worker')
    if resident is not None:
        status, bcode, _, _ = resident.get_json('/api/admin/user/page?pageNum=1&pageSize=5')
        if status == 403 and bcode == 403:
            ok('居民访问 /api/admin/** 被拒 403 JSON')
        else:
            fail('居民访问管理接口应 403，实际 %s/%s' % (status, bcode))
        status, location = resident.probe('/admin/dashboard')
        if status in (301, 302, 303, 307, 308):
            ok('居民访问 /admin/dashboard 被 %s 弹回（Location=%s）'
               % (status, location or '-'))
        else:
            fail('居民访问管理页面应重定向，实际 %s' % status)
    if worker is not None:
        status, bcode, _, _ = worker.get_json('/api/resident/order', )
        # 用 POST 才能命中写接口，这里用 GET 探测 URL 规则同样会先被角色拦截
        if status in (403, 405):
            ok('师傅访问 /api/resident/** 被角色规则拦截（%s）' % status)
        else:
            fail('师傅访问居民接口应 403/405，实际 %s/%s' % (status, bcode))

    # ---------- 4. 结果汇总 ----------
    print('=' * 68)
    total = passed + len(failures)
    print('结果：PASS %d / %d' % (passed, total))
    if failures:
        print('FAIL 明细：')
        for item in failures:
            print('  - ' + item)
        print('FINAL: FAILED')
        return 1
    print('FINAL: ALL GREEN')
    return 0


if __name__ == '__main__':
    sys.exit(main())
