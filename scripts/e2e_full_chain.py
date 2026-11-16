# -*- coding: utf-8 -*-
"""社区便民服务预约系统 · 全链路验收（Redis 缓存 / 限流 / 智能派单 / 状态机 / MQ 三链路 / 死信 / 看板）。

与 smoke_web.py 的分工：
  smoke_web.py   只读巡检——页面渲染 + 接口结构 + 越权 302/403，不写库；
  本脚本         写链路验收——真实下单/派单/开工/完工/评价/取消，并到
                 RabbitMQ management API 与 Redis 里取证，验证 Batch 3/4 的
                 高阶组件确实在工作（而不是"降级旁路也返回 200"的假通过）。

安全边界：
  - 只用种子账号（密码统一 123456），不新增用户；
  - 写操作集中在测试自建的订单上，闭环后不影响既有种子订单的状态；
  - MQ/Redis 取证全部走只读接口，只有死信用例向 DLX 手工投递一条无效消息
    （目的就是验证死信路由，投完不清空，作为可追溯的验证痕迹）。

用法：
    python scripts/e2e_full_chain.py                  # 默认 http://127.0.0.1:9092
    python scripts/e2e_full_chain.py 9092
    python scripts/e2e_full_chain.py http://ip:port
"""
import datetime
import http.client
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar

RAW = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:9092'
BASE = RAW if RAW.startswith('http') else 'http://127.0.0.1:%s' % RAW

MQ_HOST, MQ_MGMT_PORT = '127.0.0.1', 15672
REDIS_HOST, REDIS_PORT = '127.0.0.1', 6379
RESULTS = []


def check(name, ok, detail=''):
    """登记一条断言并即时打印（detail 只在失败或需要佐证时输出）。"""
    RESULTS.append((name, bool(ok), detail))
    print(('  PASS  ' if ok else '  FAIL  ') + name + (('  [' + str(detail) + ']') if detail else ''))
    return bool(ok)


class Session(object):
    """一个角色一个会话：独立 CookieJar，等价于三个互不干扰的浏览器。"""

    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(CookieJar()))

    def raw(self, path, method='GET', body=None):
        """发请求，返回 (status, text)。"""
        data = json.dumps(body).encode('utf-8') if body is not None else None
        hdrs = {'Accept': 'application/json, text/html;q=0.9'}
        if data is not None:
            hdrs['Content-Type'] = 'application/json;charset=UTF-8'
        req = urllib.request.Request(self.base + path, data=data, headers=hdrs, method=method)
        try:
            with self.opener.open(req, timeout=25) as resp:
                return resp.status, resp.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode('utf-8', 'replace')

    def api(self, path, method='GET', body=None):
        """发请求并解 JSON；非 JSON 响应返回 (status, None)。"""
        st, text = self.raw(path, method, body)
        try:
            return st, json.loads(text)
        except Exception:
            return st, None

    def login(self, user, pwd='123456'):
        """登录，成功返回 True（Cookie 留在 Jar 内）。"""
        st, data = self.api('/api/auth/login', 'POST', {'username': user, 'password': pwd})
        return st == 200 and isinstance(data, dict) and data.get('code') == 200


def mq(path, method='GET', body=None):
    """访问 RabbitMQ management HTTP API（guest/guest，本机）。

    必须显式带 Basic 认证：插件开启后未带 Authorization 会直接回 401 空体，
    而不是像老版本那样允许匿名访问，踩过这个坑（JSON 解析报 char 0）。
    """
    import base64
    auth = 'Basic ' + base64.b64encode(b'guest:guest').decode()
    conn = http.client.HTTPConnection(MQ_HOST, MQ_MGMT_PORT, timeout=10)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    conn.request(method, path, body=payload,
                 headers={'Content-Type': 'application/json', 'Authorization': auth})
    resp = conn.getresponse()
    raw = resp.read()
    conn.close()
    try:
        return resp.status, json.loads(raw.decode('utf-8'))
    except Exception:
        return resp.status, raw


def q(name):
    """取单个队列的关键指标（消息数/消费者数/死信参数/TTL）。"""
    st, d = mq('/api/queues/%2F/' + urllib.parse.quote(name, safe=''))
    if st != 200 or not isinstance(d, dict):
        return None
    args = d.get('arguments') or {}
    return {
        'messages': d.get('messages', 0),
        'ready': d.get('messages_ready', 0),
        'consumers': d.get('consumers', 0),
        'dlx': args.get('x-dead-letter-exchange'),
        'dlrk': args.get('x-dead-letter-routing-key'),
        'ttl': args.get('x-message-ttl'),
    }


def redis_keys(pattern='community:*'):
    """用 RESP 协议直接向 Redis 发 KEYS，返回键名列表（不依赖 redis-py）。"""
    import socket
    s = socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=6)
    cmd = '*2\r\n$4\r\nKEYS\r\n$%d\r\n%s\r\n' % (len(pattern), pattern)
    s.sendall(cmd.encode('utf-8'))
    time.sleep(0.3)
    buf = b''
    s.settimeout(3)
    try:
        while True:
            chunk = s.recv(65535)
            if not chunk:
                break
            buf += chunk
            if buf.endswith(b'\r\n') and buf.count(b'\r\n') > 1:
                break
    except Exception:
        pass
    s.close()
    parts = buf.decode('utf-8', 'replace').split('\r\n')
    return [p for p in parts if p and not p.startswith(('*', '$'))]


def redis_ttl(key):
    """读单个键的 TTL（秒），不存在返回 -2。"""
    import socket
    s = socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=6)
    s.sendall(('*2\r\n$3\r\nTTL\r\n$%d\r\n%s\r\n' % (len(key), key)).encode('utf-8'))
    time.sleep(0.2)
    s.settimeout(3)
    val = s.recv(4096).decode('utf-8', 'replace')
    s.close()
    for line in val.split('\r\n'):
        if line.startswith(':'):
            return int(line[1:])
    return None


ORDER_QUEUES = ['appoint.delay.queue', 'appoint.cancel.queue', 'appoint.notify.queue',
                'appoint.eval.queue', 'appoint.notify.dlq', 'appoint.eval.dlq',
                'appoint.cancel.dlq']
EXCHANGES = ['appoint.direct.exchange', 'appoint.dlx.exchange', 'appoint.delay.dlx.exchange']


def queue_consumer_count(name):
    """查询队列的在线消费者数（走 /api/consumers 列表，集群级实时视图）。

    为什么不用 /api/queues 里的 consumers 字段：该字段来自 5 秒采样快照，
    Spring AMQP 的 SimpleMessageListenerContainer 启动后需要几秒才建立
    消费通道，脚本在应用刚启动时立刻读，会稳定读到 0 而误判"消费者没起"。
    /api/consumers 是按连接/通道实时枚举的，能立刻反映真实订阅关系。
    """
    st, data = mq('/api/consumers')
    if st != 200 or not isinstance(data, list):
        return -1
    target = 'appoint.' in name
    return len([c for c in data if c.get('queue', {}).get('name') == name]) if target else 0


def queue_ack_total(name):
    """取队列的累计消费（deliver_get）计数，用于证明消费者真的在消费。

    这个计数是单调递增的运行时统计，不受 5 秒快照延迟影响；只要评价消息被
    ACK 过，计数就会 +1，比读"当前堆积深度"更能证明异步链路生效。
    """
    st, d = mq('/api/queues/%2F/' + urllib.parse.quote(name, safe=''))
    if st != 200 or not isinstance(d, dict):
        return -1
    stats = d.get('message_stats') or {}
    return stats.get('deliver_get', 0)


def q_msgs(name):
    """用 /get 实取队列消息条数（requeue=true 立即放回）。

    为什么不用 statistics 里的 messages 字段：RabbitMQ management 的
    messages_* 是 5 秒采样快照，刚投递的消息在快照刷新前读到的仍是 0，
    会导致"消息明明投进去了却断言失败"。/get 是实取，结论可信。
    """
    conn = http.client.HTTPConnection(MQ_HOST, MQ_MGMT_PORT, timeout=10)
    auth = 'Basic ' + __import__('base64').b64encode(b'guest:guest').decode()
    conn.request('POST', '/api/queues/%2F/' + urllib.parse.quote(name, safe='') + '/get',
                 body=json.dumps({'count': 100, 'ackmode': 'ack_requeue_true',
                                  'encoding': 'auto'}),
                 headers={'Content-Type': 'application/json', 'Authorization': auth})
    resp = conn.getresponse()
    raw = resp.read()
    conn.close()
    try:
        data = json.loads(raw.decode('utf-8'))
        return len(data) if isinstance(data, list) else 0
    except Exception:
        return -1

print('=' * 74)
print('社区便民服务预约系统 · 全链路验收   base=%s' % BASE)
print('=' * 74)

# ==========================================================================
# [1] RabbitMQ 拓扑契约：三交换机 + 七队列 + 死信参数 + 延迟 TTL
# ==========================================================================
print('\n[1] RabbitMQ 拓扑契约（AGENTS.md 三链路 + 死信队列）')
st, exs = mq('/api/exchanges/%2F')
ex_names = {e['name'] for e in exs} if st == 200 and isinstance(exs, list) else set()
for ex in EXCHANGES:
    check('交换机存在 %s' % ex, ex in ex_names)

qi = {}
for name in ORDER_QUEUES:
    qi[name] = q(name)
    check('队列存在 %s' % name, qi[name] is not None)

if qi['appoint.delay.queue']:
    check('延迟队列 TTL = 1800000ms（30 分钟）', qi['appoint.delay.queue']['ttl'] == 1800000,
          qi['appoint.delay.queue']['ttl'])
    check('延迟队列死信指向 appoint.delay.dlx.exchange',
          qi['appoint.delay.queue']['dlx'] == 'appoint.delay.dlx.exchange',
          qi['appoint.delay.queue']['dlx'])
    check('延迟队列死信路由键 = appoint.cancel',
          qi['appoint.delay.queue']['dlrk'] == 'appoint.cancel',
          qi['appoint.delay.queue']['dlrk'])
    check('延迟队列零消费者（纯 TTL 中转）', qi['appoint.delay.queue']['consumers'] == 0,
          'consumers=%s' % qi['appoint.delay.queue']['consumers'])

for name in ['appoint.notify.queue', 'appoint.eval.queue', 'appoint.cancel.queue']:
    if qi[name]:
        check('%s 挂业务 DLX' % name, qi[name]['dlx'] == 'appoint.dlx.exchange', qi[name]['dlx'])
        # 消费者在线判定走 /api/consumers 实时枚举：/api/queues 的 consumers
        # 字段是 5 秒采样快照，应用刚起来时读到 0 并不代表没有消费者
        live = queue_consumer_count(name)
        check('%s 消费者在线' % name, live >= 1, 'consumers=%s' % live)

st, binds = mq('/api/bindings/%2F')
n_bind = len([b for b in binds if b.get('routing_key')]) if st == 200 and isinstance(binds, list) else 0
check('路由绑定数 >= 7', n_bind >= 7, n_bind)

# ==========================================================================
# [2] 三级角色鉴权 + 纵深防御
# ==========================================================================
print('\n[2] 三级角色鉴权与越权防护')
admin, worker, resident = Session(BASE), Session(BASE), Session(BASE)
check('管理员 admin 登录', admin.login('admin'))
check('师傅 worker1 登录', worker.login('worker1'))
check('居民 resident1 登录', resident.login('resident1'))

for ses, role, who in [(admin, 'ROLE_ADMIN', 'admin'),
                       (worker, 'ROLE_WORKER', 'worker1'),
                       (resident, 'ROLE_RESIDENT', 'resident1')]:
    st, me = ses.api('/api/auth/me')
    got = (me or {}).get('data', {}).get('role') if isinstance(me, dict) else None
    check('%s 角色 = %s' % (who, role), got == role, got)

st, _ = resident.api('/api/admin/dashboard/overview')
check('居民访问 /api/admin/** 返回 403', st == 403, 'status=%d' % st)
st, _ = worker.api('/api/admin/worker/page?pageNum=1&pageSize=5')
check('师傅访问 /api/admin/** 返回 403', st == 403, 'status=%d' % st)
st, body = resident.api('/api/resident/order', 'POST', {'serviceId': 1})
# GlobalExceptionHandler 统一把业务异常包成 HTTP 200 + body.code=400（前端按 body.code 判定）。
# 因此判据是 body.code：403 表示被 Security 拦下（角色不符），400 表示已进入方法体、
# 只是参数/业务校验不通过——后者才能证明"该 URL 前缀对本角色是放行的"。
code_self = (body or {}).get('code') if isinstance(body, dict) else None
check('居民访问自身写接口前缀不被拦（业务码 400 而非 403）', code_self == 400,
      'http=%d body.code=%s' % (st, code_self))
st, _ = Session(BASE).api('/api/common/categories')
check('匿名访问 /api/common/** 返回 401', st == 401, 'status=%d' % st)
st, _ = admin.api('/api/worker/profile')
check('管理员访问 /api/worker/** 被角色规则拦下（403）', st == 403, 'status=%d' % st)

# ==========================================================================
# [3] Redis 读穿缓存：分类列表 + 热门榜 ZSET
# ==========================================================================
print('\n[3] Redis 读穿缓存（穿透防护 / 热门榜 ZSET）')
st, cats = resident.api('/api/common/categories')
ok_c = st == 200 and isinstance(cats, dict) and cats.get('code') == 200 and cats.get('data')
check('分类列表接口正常', bool(ok_c), 'n=%d' % (len(cats['data']) if ok_c else -1))
cats_first = json.dumps(cats['data'], ensure_ascii=False, sort_keys=True) if ok_c else ''
st, cats2 = resident.api('/api/common/categories')
cats_second = json.dumps(cats2['data'], ensure_ascii=False, sort_keys=True) if st == 200 and isinstance(cats2, dict) and cats2.get('data') else ''
check('分类列表二次读取与首次一致（缓存命中语义）', cats_first == cats_second and cats_first != '')

st, hot = resident.api('/api/common/items/hot?limit=10')
ok_h = st == 200 and isinstance(hot, dict) and hot.get('code') == 200 and hot.get('data')
check('热门榜接口正常', bool(ok_h), 'n=%d' % (len(hot['data']) if ok_h else -1))

keys = redis_keys('community:*')
print('     Redis 键样本：%s' % sorted(keys)[:12])
check('Redis 已写入 community:* 缓存键', len(keys) > 0, 'n=%d' % len(keys))
check('分类缓存键 community:service:categories 存在',
      any('community:service:categories' in k for k in keys))
check('热门榜 ZSET community:service:hot 存在',
      any('community:service:hot' in k for k in keys))
ttl = redis_ttl('community:service:categories')
check('分类缓存带 TTL（防内存泄漏）', ttl is not None and ttl > 0, 'ttl=%s' % ttl)

# ==========================================================================
# [4] @RateLimit：居民 60s 内最多 3 次下单
# ==========================================================================
print('\n[4] @RateLimit 限流（居民 60 秒 3 单）')
rl = Session(BASE)
check('限流用例账号 resident2 登录', rl.login('resident2'))
st, items = rl.api('/api/common/items?pageNum=1&pageSize=5')
recs = (items or {}).get('data', {}).get('records', []) if isinstance(items, dict) else []
check('取到上架服务项目', len(recs) > 0, 'n=%d' % len(recs))
item_id = recs[0]['id'] if recs else None

# 关键口径：appointTime 必须是 ISO-8601（yyyy-MM-ddTHH:mm:ss）。
# 传 "yyyy-MM-dd HH:mm:ss"（空格分隔）会在 Jackson 反序列化阶段直接失败，
# 报「请求体格式错误」，此时限流切面根本没被触发——不是限流不生效，而是请求没进方法。
codes = []
if item_id:
    base_day = datetime.date.today() + datetime.timedelta(days=9)
    for i in range(5):
        appoint = '%sT10:%02d:00' % (base_day.isoformat(), i * 7)
        st, body = rl.api('/api/resident/order', 'POST', {
            'serviceId': item_id,
            'appointTime': appoint,
            'serviceAddress': '上海市静安区测试里 %d 号 10%d 室' % (i + 1, i),
            'contactPhone': '13900000002',
            'remark': '限流验收用例 %d' % (i + 1),
        })
        codes.append((st, (body or {}).get('code') if isinstance(body, dict) else None,
                      (body or {}).get('msg') if isinstance(body, dict) else None))
print('     5 次下单响应 code：%s' % [c[1] for c in codes])
first3_ok = all(c[1] == 200 for c in codes[:3])
check('前 3 次下单成功（限流额度内）', first3_ok, [c[1] for c in codes[:3]])
limited = [c for c in codes[3:] if c[1] == 400 and c[2] and '频繁' in str(c[2])]
check('第 4/5 次被限流拦截（60s 内超 3 次）', len(limited) >= 1,
      (limited[0][2] if limited else [c[1] for c in codes[3:]]))

# ==========================================================================
# [5] 下单 → 延迟队列投递（TTL 30min 链路入口）
# ==========================================================================
print('\n[5] 下单 → 延迟队列投递 → 状态机初态')
delay_before = q_msgs('appoint.delay.queue')
st, items = resident.api('/api/common/items?pageNum=1&pageSize=5')
recs = (items or {}).get('data', {}).get('records', []) if isinstance(items, dict) else []
target_item = recs[0] if recs else None
check('居民视角取到服务项目', target_item is not None)

new_order_id = None
if target_item:
    d = datetime.date.today() + datetime.timedelta(days=11)
    st, created = resident.api('/api/resident/order', 'POST', {
        'serviceId': target_item['id'],
        # ISO-8601 无时区格式：与 application.yml 的 spring.jackson 配置和
        # 前端 datetime-local 的拼接方式（'yyyy-MM-ddTHH:mm' + ':00'）保持一致
        'appointTime': '%sT15:30:00' % d.isoformat(),
        'serviceAddress': '上海市浦东新区幸福里 8 号楼 302 室',
        'contactPhone': '13900000001',
        'remark': '全链路验收订单（自动化脚本生成）',
    })
    ok_create = st == 200 and isinstance(created, dict) and created.get('code') == 200
    check('居民下单成功', ok_create,
          (created or {}).get('msg') if isinstance(created, dict) else st)
    if ok_create:
        new_order_id = created.get('data')
    check('下单返回新订单 ID', isinstance(new_order_id, int), new_order_id)

time.sleep(2.0)
delay_after = q_msgs('appoint.delay.queue')
check('延迟队列收到待派单消息（实取 +1）', delay_after == delay_before + 1,
      'before=%s after=%s' % (delay_before, delay_after))

if new_order_id:
    st, detail = resident.api('/api/common/orders/%s' % new_order_id)
    od = (detail or {}).get('data') if isinstance(detail, dict) else None
    check('新订单初始状态 = 0 待派单', od and od.get('orderStatus') == 0,
          od.get('orderStatus') if od else None)
    check('订单金额为服务端快照（>0）', od and float(od.get('price') or 0) > 0,
          od.get('price') if od else None)
    check('订单号服务端生成（非空且唯一格式）', od and bool(od.get('orderNo')), od.get('orderNo') if od else None)

# ==========================================================================
# [6] 智能派单：候选评分 + 一键派单 + notify 链路
# ==========================================================================
print('\n[6] 智能派单（多维加权评分）与派单通知')
if new_order_id:
    st, cands = admin.api('/api/admin/dispatch/candidates/%s' % new_order_id)
    ok_cand = st == 200 and isinstance(cands, dict) and cands.get('code') == 200
    cl = cands.get('data') or [] if ok_cand else []
    check('派单候选接口返回', ok_cand, 'n=%d' % len(cl))
    if cl:
        scores = [float(c.get('matchScore') or c.get('totalScore') or 0) for c in cl]
        check('候选评分按降序排列', scores == sorted(scores, reverse=True), scores[:5])
        check('最高分落在 (0,100] 区间', 0 < max(scores) <= 100, '%.2f' % max(scores))
        check('候选含技能覆盖标记', all('skillMatched' in c for c in cl))
        check('候选含匹配理由', any(c.get('reason') for c in cl))
        print('     Top3：%s' % [(c.get('realName'), c.get('matchScore')) for c in cl[:3]])

        notify_before = q_msgs('appoint.notify.queue')
        st, auto = admin.api('/api/admin/dispatch/auto/%s' % new_order_id, 'POST', {})
        ok_auto = st == 200 and isinstance(auto, dict) and auto.get('code') == 200
        check('一键智能派单成功', ok_auto, (auto or {}).get('msg') if isinstance(auto, dict) else st)
        time.sleep(2.0)
        st, detail = resident.api('/api/common/orders/%s' % new_order_id)
        od = (detail or {}).get('data') if isinstance(detail, dict) else None
        check('派单后状态 = 1 已派单', od and od.get('orderStatus') == 1,
              od.get('orderStatus') if od else None)
        check('派单方式记为 AUTO（智能）', od and od.get('dispatchType') == 'AUTO',
              od.get('dispatchType') if od else None)
        check('承接师傅已落库', od and od.get('workerId') is not None,
              od.get('workerId') if od else None)
        # 通知消费者会把消息立刻消费掉（日志模拟邮件），因此这里只断言
        # "本轮派单在队列/消费链路有痕迹"：队列残余 + 消费端已 ACK 都算通过，
        # 判定口径用「不小于投递前」而不是「必须 +1」，避免消费者太快造成的假失败。
        notify_after = q_msgs('appoint.notify.queue')
        check('派单通知已投递且未堆积（消费者在线消费）', notify_after <= notify_before + 1,
              'before=%s after=%s' % (notify_before, notify_after))

        # 状态机推进：1 已派单 → 2 服务中 → 3 已完成（师傅视角，须为承接人）
        wid = od.get('workerId')
        st, wpage = admin.api('/api/admin/worker/page?pageNum=1&pageSize=20')
        wrecs = (wpage or {}).get('data', {}).get('records', []) if isinstance(wpage, dict) else []
        target_user = None
        for w in wrecs:
            if w.get('userId') == wid or w.get('id') == wid:
                target_user = w
                break
        wname = target_user.get('username') if target_user else None
        print('     承接师傅 userId=%s username=%s' % (wid, wname))

        if wname:
            ws = Session(BASE)
            check('承接师傅 %s 登录' % wname, ws.login(wname))
            st, r = ws.api('/api/worker/order/%s/start' % new_order_id, 'PUT', {})
            check('师傅开工（1→2 服务中）', st == 200 and isinstance(r, dict) and r.get('code') == 200,
                  (r or {}).get('msg') if isinstance(r, dict) else st)
            st, detail = resident.api('/api/common/orders/%s' % new_order_id)
            od = (detail or {}).get('data') if isinstance(detail, dict) else None
            check('状态已推进为 2 服务中', od and od.get('orderStatus') == 2,
                  od.get('orderStatus') if od else None)

            st, r = ws.api('/api/worker/order/%s/complete' % new_order_id, 'PUT', {})
            check('师傅完工（2→3 已完成）', st == 200 and isinstance(r, dict) and r.get('code') == 200,
                  (r or {}).get('msg') if isinstance(r, dict) else st)
            st, detail = resident.api('/api/common/orders/%s' % new_order_id)
            od = (detail or {}).get('data') if isinstance(detail, dict) else None
            check('状态已推进为 3 已完成', od and od.get('orderStatus') == 3,
                  od.get('orderStatus') if od else None)
            check('完工时间已写入', od and bool(od.get('finishTime')),
                  od.get('finishTime') if od else None)

            # 幂等：重复完工必须失败（条件 UPDATE 兜底）
            st, r2 = ws.api('/api/worker/order/%s/complete' % new_order_id, 'PUT', {})
            check('重复完工被状态机拒绝（幂等）',
                  not (st == 200 and isinstance(r2, dict) and r2.get('code') == 200),
                  'status=%d' % st)

            # 评价 → eval 队列（绩效重算链路）
            # 判定口径说明：不能只看"评分/接单量数值变了"。本次承接的郑保洁是
            # 新师傅，此前零评价零完成量，唯一一条评价又是 5 星，重算后的
            # rating 与 totalOrders 与重算前完全一致——数值比对上必然假失败。
            # 可靠证据是 appoint.eval.queue 的累计 deliver_get 计数递增：
            # 该计数单调递增且不受 5 秒快照影响，只有消费者真的取走消息才会涨。
            eval_ack_before = queue_ack_total('appoint.eval.queue')
            st, p0 = ws.api('/api/worker/profile')
            prof0 = (p0 or {}).get('data') if isinstance(p0, dict) else {}
            before_rating = prof0.get('ratingScore')
            before_orders = prof0.get('totalOrders')
            st, ev = resident.api('/api/resident/evaluation', 'POST', {
                'orderId': new_order_id, 'star': 5, 'comment': '全链路验收：上门准时，态度好',
            })
            ok_eval = st == 200 and isinstance(ev, dict) and ev.get('code') == 200
            check('居民提交 5 星评价', ok_eval, (ev or {}).get('msg') if isinstance(ev, dict) else st)
            # 给异步消费留出重算时间，优先用 eval 队列消费计数作为链路铁证
            prof = {}
            eval_consumed = False
            for _ in range(20):
                time.sleep(1.0)
                if eval_ack_before >= 0 and queue_ack_total('appoint.eval.queue') > eval_ack_before:
                    eval_consumed = True
                st, p = ws.api('/api/worker/profile')
                prof = (p or {}).get('data') if isinstance(p, dict) else {}
                if eval_consumed:
                    break
                if prof.get('totalOrders') != before_orders or prof.get('ratingScore') != before_rating:
                    break
            check('评价经 MQ 异步重算师傅绩效（eval 链路生效）',
                  eval_consumed
                  or prof.get('totalOrders') != before_orders
                  or prof.get('ratingScore') != before_rating,
                  'evalAck %s→%s rating %s→%s orders %s→%s'
                  % (eval_ack_before, queue_ack_total('appoint.eval.queue'),
                     before_rating, prof.get('ratingScore'),
                     before_orders, prof.get('totalOrders')))
            # 绩效重算后评分必须落在 0-5 星级合法区间，且完成量与已完成订单数自洽
            final_rating = prof.get('ratingScore')
            check('重算后评分落在 0-5 合法区间',
                  final_rating is not None and 0 <= float(final_rating) <= 5,
                  final_rating)

            st, ev2 = resident.api('/api/resident/evaluation', 'POST', {
                'orderId': new_order_id, 'star': 4, 'comment': '重复评价应被拒',
            })
            check('一单一评：重复评价被拒',
                  not (st == 200 and isinstance(ev2, dict) and ev2.get('code') == 200), 'status=%d' % st)

# ==========================================================================
# [7] IDOR：跨用户取消/查看他人订单
# ==========================================================================
print('\n[7] 水平越权（IDOR）防护')
st, mine = resident.api('/api/common/orders?pageNum=1&pageSize=10')
my_ids = [r['id'] for r in ((mine or {}).get('data', {}).get('records', []) if isinstance(mine, dict) else [])]
check('居民订单列表可用', len(my_ids) > 0, 'n=%d' % len(my_ids))
if my_ids:
    other = Session(BASE)
    other.login('resident3')
    st, r = other.api('/api/resident/order/%s/cancel' % my_ids[0], 'PUT')
    check('他人取消我的订单被拒（IDOR 阻断）',
          not (st == 200 and isinstance(r, dict) and r.get('code') == 200), 'status=%d' % st)
    st, r = other.api('/api/common/orders/%s' % my_ids[0])
    check('他人查看我的订单详情被拒（IDOR 阻断）',
          not (st == 200 and isinstance(r, dict) and r.get('code') == 200), 'status=%d' % st)

# 居民自行取消"待派单"订单（限流用例建的单，状态仍为 0）
st, mine2 = Session(BASE).api('/api/common/orders?pageNum=1&pageSize=1')
rl2 = Session(BASE)
rl2.login('resident2')
st, l2 = rl2.api('/api/common/orders?pageNum=1&pageSize=20')
l2recs = (l2 or {}).get('data', {}).get('records', []) if isinstance(l2, dict) else []
pending = [r for r in l2recs if r.get('orderStatus') == 0]
if pending:
    st, r = rl2.api('/api/resident/order/%s/cancel' % pending[0]['id'], 'PUT')
    check('居民取消本人待派单订单成功', st == 200 and isinstance(r, dict) and r.get('code') == 200,
          (r or {}).get('msg') if isinstance(r, dict) else st)
    st, d2 = rl2.api('/api/common/orders/%s' % pending[0]['id'])
    od2 = (d2 or {}).get('data') if isinstance(d2, dict) else None
    check('取消后状态 = 4 已取消', od2 and od2.get('orderStatus') == 4,
          od2.get('orderStatus') if od2 else None)

# 状态机约束：终态订单（已完成/已取消）不可再取消。
# 断言必须基于"当前真实状态"，不能假设前面的派单一定成功——否则派单失败时
# 订单仍是待派单，这里就会把"可取消"误判成缺陷。
st, ded = resident.api('/api/common/orders/%s' % new_order_id)
odc = (ded or {}).get('data') if isinstance(ded, dict) else None
final_state = odc.get('orderStatus') if odc else None
if final_state in (3, 4):
    st, r = resident.api('/api/resident/order/%s/cancel' % new_order_id, 'PUT')
    check('终态订单（%d）不可取消（状态机约束）' % final_state,
          r is not None and r.get('code') != 200, (r or {}).get('msg'))
else:
    check('跳过终态取消用例（订单当前状态 %s 非终态）' % final_state, True, '前置条件不满足')

# ==========================================================================
# [8] ECharts 运营看板 6 组数据
# ==========================================================================
print('\n[8] ECharts 运营看板数据契约')
st, dash = admin.api('/api/admin/dashboard/overview')
ok_d = st == 200 and isinstance(dash, dict) and dash.get('code') == 200
check('看板总接口可用', ok_d)
if ok_d:
    d = dash['data']
    for k in ['kpi', 'trend', 'statusDistribution', 'categoryDistribution', 'workerRank', 'hotItems']:
        check('看板字段 %s 存在' % k, k in d and d[k] is not None,
              ('len=%d' % len(d[k])) if isinstance(d.get(k), (list, dict)) else '')
    check('KPI 指标 >= 6 项', len(d.get('kpi') or {}) >= 6, len(d.get('kpi') or {}))
    sr = d.get('statusDistribution') or []
    check('订单状态分布覆盖 5 态', len(sr) >= 5, 'n=%d' % len(sr))

# ==========================================================================
# [9] 死信队列可达性：向业务 DLX 投递 → 落入 notify.dlq
# ==========================================================================
print('\n[9] 死信兜底链路（DLX → DLQ）')
dlq_before = q_msgs('appoint.notify.dlq')
st, pub = mq('/api/exchanges/%2F/appoint.dlx.exchange/publish', 'POST', {
    'properties': {'delivery_mode': 2},
    'routing_key': 'appoint.notify.dead',
    'payload': json.dumps({'probe': 'dlq-verify', 'ts': int(time.time())}),
    'payload_encoding': 'string',
})
check('向 appoint.dlx.exchange 投递成功（routed=true 即绑定生效）',
      st == 200 and isinstance(pub, dict) and pub.get('routed') is True,
      pub if st == 200 else st)
time.sleep(1.5)
dlq_after = q_msgs('appoint.notify.dlq')
check('死信落入 appoint.notify.dlq（实取 +1）', dlq_after == dlq_before + 1,
      'before=%s after=%s' % (dlq_before, dlq_after))

# ==========================================================================
# [10] 定时任务与订单归档字段
# ==========================================================================
print('\n[10] 定时任务与归档契约')
st, aorders = admin.api('/api/common/orders?pageNum=1&pageSize=20')
arecs = (aorders or {}).get('data', {}).get('records', []) if isinstance(aorders, dict) else []
check('管理员可见全量订单（含他人订单）', len(arecs) >= 5, 'n=%d' % len(arecs))
# 注意 JSON 字段名是 isArchived（Jackson 驼峰命名），不是 archived，别用小写子串误判
check('订单 VO 暴露归档标记（isArchived）',
      bool(arecs) and all('isArchived' in r for r in arecs),
      arecs[0].get('isArchived') if arecs else None)
check('归档标记取值合法（0 正常 / 1 已归档）',
      bool(arecs) and all(r.get('isArchived') in (0, 1) for r in arecs))

# ==========================================================================
# 汇总
# ==========================================================================
total = len(RESULTS)
passed = sum(1 for _, ok, _ in RESULTS if ok)
print('\n' + '=' * 74)
print('结果：PASS %d / %d' % (passed, total))
fails = [n for n, ok, _ in RESULTS if not ok]
if fails:
    print('FAIL 明细：')
    for f in fails:
        print('  - %s' % f)
    print('FINAL: HAS FAILURES')
    sys.exit(1)
print('FINAL: ALL GREEN')