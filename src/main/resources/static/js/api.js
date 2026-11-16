/* =====================================================================
 * 社区便民服务预约系统 · 前端公共库（jQuery 之上的薄封装）
 * ---------------------------------------------------------------------
 * 统一约定：
 *   1. 后端所有接口返回 { code, msg, data }，code=200 视为成功；
 *   2. 失败统一 reject(Error(msg))，页面用 .catch(CM.alertError) 兜底；
 *   3. 未登录（HTTP 401 / 业务 code 401）跳转 /login，避免"静默空白页"；
 *      已在 /login 时不再跳转，否则登录页的 /api/auth/me 探测会把自己刷成死循环；
 *   4. 只做"表现层"封装（格式化、分页、提示），不掺业务规则。
 * ===================================================================== */
window.CM = (function () {
    'use strict';

    /** 角色 -> 工作台首页（登录成功后按角色分流） */
    var ROLE_HOME = {
        ROLE_RESIDENT: '/resident/home',
        ROLE_WORKER: '/worker/workbench',
        ROLE_ADMIN: '/admin/dashboard'
    };

    /** 角色中文名 */
    var ROLE_TEXT = {
        ROLE_RESIDENT: '社区居民',
        ROLE_WORKER: '服务人员',
        ROLE_ADMIN: '物业管理员'
    };

    /** 订单状态 -> 徽章样式类与中文（与后端 Constants 一一对应） */
    var STATUS_META = {
        0: { text: '待派单', cls: 'st-0' },
        1: { text: '已派单', cls: 'st-1' },
        2: { text: '服务中', cls: 'st-2' },
        3: { text: '已完成', cls: 'st-3' },
        4: { text: '已取消', cls: 'st-4' }
    };

    /** 当前登录用户缓存（同一页面多次读取不重复请求） */
    var meCache = null;

    /** 是否已在跳转登录页的过程中（防止多个并发 401 触发多次跳转） */
    var redirecting = false;

    /**
     * 401 统一出口：回登录页，但若本来就站在登录页则什么都不做。
     *
     * 为什么需要这个判断：登录页加载时会探测一次 /api/auth/me，未登录必然 401。
     * 若此处不加判断直接写 location.href，浏览器会「重新加载登录页 → 再探测 →
     * 再 401」无限循环，表现为页面疯狂闪烁、网络面板里 401 刷屏。
     *
     * @returns {void}
     */
    function redirectToLoginOnce() {
        var here = window.location.pathname.replace(/\/+$/, '');
        if (here === '/login' || redirecting) {
            return;
        }
        redirecting = true;
        window.location.href = '/login';
    }

    /**
     * 统一请求：拼 URL、发 AJAX、解包 Result、统一错误提示。
     *
     * @param {string} method  HTTP 方法
     * @param {string} url     接口地址
     * @param {object} data    请求体（POST/PUT）或查询参数（GET）
     * @param {object} options 额外选项：{ silent: true } 表示不自动弹错误提示
     * @returns {Promise<*>} 成功时 resolve(data)
     */
    function request(method, url, data, options) {
        var opts = options || {};
        var settings = {
            url: url,
            type: method,
            dataType: 'json',
            headers: { 'X-Requested-With': 'XMLHttpRequest' }
        };
        if (method === 'GET') {
            settings.data = data || {};
        } else {
            settings.contentType = 'application/json; charset=UTF-8';
            settings.data = JSON.stringify(data || {});
        }
        return new Promise(function (resolve, reject) {
            $.ajax(settings)
                .done(function (res) {
                    if (res && res.code === 200) {
                        resolve(res.data);
                        return;
                    }
                    var msg = (res && res.msg) || '请求处理失败';
                    if (res && res.code === 401) {
                        // 会话失效：直接回登录页，避免页面继续操作产生更多 401。
                        // 若当前已在登录页则不跳转：登录页自身会探测 /api/auth/me，
                        // 未登录时必然 401，此时再赋 location.href 会造成
                        // 「加载登录页 → 探测 401 → 重新加载登录页」的无限刷新。
                        redirectToLoginOnce();
                        return;
                    }
                    if (!opts.silent) {
                        toast(msg, 'error');
                    }
                    reject(new Error(msg));
                })
                .fail(function (xhr) {
                    if (xhr.status === 401) {
                        redirectToLoginOnce();
                        return;
                    }
                    var body = xhr.responseJSON;
                    var msg = (body && body.msg) || ('请求失败（HTTP ' + xhr.status + '）');
                    if (!opts.silent) {
                        toast(msg, 'error');
                    }
                    reject(new Error(msg));
                });
        });
    }

    /** GET 请求快捷方法 */
    function get(url, params, options) {
        return request('GET', url, params, options);
    }

    /** POST 请求快捷方法 */
    function post(url, body, options) {
        return request('POST', url, body, options);
    }

    /** PUT 请求快捷方法 */
    function put(url, body, options) {
        return request('PUT', url, body, options);
    }

    /** DELETE 请求快捷方法 */
    function del(url, options) {
        return request('DELETE', url, null, options);
    }

    /**
     * 轻提示（右上角浮层，2.2 秒自动消失）。
     *
     * @param {string} msg  提示文案
     * @param {string} type success | error | info
     */
    function toast(msg, type) {
        var wrap = $('.cm-toast-wrap');
        if (!wrap.length) {
            wrap = $('<div class="cm-toast-wrap"></div>').appendTo('body');
        }
        var $t = $('<div class="cm-toast"></div>').addClass(type || 'info').text(msg).appendTo(wrap);
        setTimeout(function () {
            $t.fadeOut(160, function () {
                $t.remove();
            });
        }, 2200);
    }

    /**
     * 二次确认弹窗（Promise 化，替代原生 confirm 的丑陋样式）。
     *
     * @param {string} message 确认文案
     * @param {string} okText  确认按钮文案
     * @returns {Promise<boolean>}
     */
    function confirmDialog(message, okText) {
        return new Promise(function (resolve) {
            var id = 'cmConfirm' + Date.now();
            var html = ''
                + '<div class="modal fade" id="' + id + '" tabindex="-1">'
                + '  <div class="modal-dialog modal-dialog-centered modal-sm">'
                + '    <div class="modal-content">'
                + '      <div class="modal-body" style="padding:22px 20px 8px;font-size:14px;line-height:1.8;">'
                + escapeHtml(message) + '</div>'
                + '      <div class="modal-footer" style="border:0;">'
                + '        <button type="button" class="btn btn-light btn-sm" data-bs-dismiss="modal">取消</button>'
                + '        <button type="button" class="btn btn-primary btn-sm" data-cm-ok>'
                + escapeHtml(okText || '确认') + '</button>'
                + '      </div>'
                + '    </div>'
                + '  </div>'
                + '</div>';
            var $m = $(html).appendTo('body');
            var modal = new bootstrap.Modal($m[0]);
            var answered = false;
            $m.find('[data-cm-ok]').on('click', function () {
                answered = true;
                modal.hide();
                resolve(true);
            });
            $m.on('hidden.bs.modal', function () {
                if (!answered) {
                    resolve(false);
                }
                $m.remove();
            });
            modal.show();
        });
    }

    /**
     * 渲染分页条（pageNum 从 1 开始，最多显示 5 个页码按钮）。
     *
     * @param {jQuery} $el   容器
     * @param {object} page  后端 PageVO：{ pageNum, pageSize, total, pages }
     * @param {function} onGo 点击页码回调，入参为目标页
     */
    function pager($el, page, onGo) {
        if (!page) {
            $el.empty();
            return;
        }
        var cur = Number(page.pageNum || 1);
        var totalPages = Number(page.pages || 0);
        var total = Number(page.total || 0);
        var start = Math.max(1, cur - 2);
        var end = Math.min(totalPages, start + 4);
        start = Math.max(1, end - 4);
        var html = '<div>共 ' + total + ' 条 · 第 ' + cur + '/' + Math.max(totalPages, 1) + ' 页</div>'
            + '<div class="pages">'
            + '<button type="button" data-go="' + (cur - 1) + '"' + (cur <= 1 ? ' disabled' : '') + '>上一页</button>';
        if (totalPages === 0) {
            html += '<button type="button" class="active" disabled>1</button>';
        }
        for (var i = start; i <= end; i++) {
            html += '<button type="button" data-go="' + i + '"' + (i === cur ? ' class="active"' : '') + '>' + i + '</button>';
        }
        html += '<button type="button" data-go="' + (cur + 1) + '"'
            + (cur >= totalPages ? ' disabled' : '') + '>下一页</button></div>';
        $el.html(html);
        $el.find('button[data-go]').on('click', function () {
            var go = Number($(this).data('go'));
            if (go >= 1 && go <= totalPages && go !== cur) {
                onGo(go);
            }
        });
    }

    /**
     * 时间格式化：兼容后端可能输出的 "2026-11-11T12:30:00" 与 "2026-11-11 12:30:00"。
     *
     * @param {string} value 时间字符串
     * @param {boolean} withSecond 是否显示秒
     * @returns {string}
     */
    function fmtTime(value, withSecond) {
        if (!value) {
            return '-';
        }
        var s = String(value).replace('T', ' ');
        s = s.replace(/\.\d+$/, '');
        return withSecond ? s : s.substring(0, 16);
    }

    /**
     * 日期格式化（取 yyyy-MM-dd 部分）。
     *
     * @param {string} value 时间字符串
     * @returns {string}
     */
    function fmtDate(value) {
        if (!value) {
            return '-';
        }
        return String(value).replace('T', ' ').substring(0, 10);
    }

    /**
     * 金额格式化（保留两位小数）。
     *
     * @param {number|string} value 金额
     * @returns {string}
     */
    function money(value) {
        var n = Number(value);
        return isNaN(n) ? '0.00' : n.toFixed(2);
    }

    /**
     * 订单状态徽章 HTML。
     *
     * @param {number} status 状态码
     * @returns {string}
     */
    function statusBadge(status) {
        var meta = STATUS_META[status] || { text: '未知', cls: 'st-4' };
        return '<span class="badge-soft ' + meta.cls + '">' + meta.text + '</span>';
    }

    /**
     * 角色徽章 HTML。
     *
     * @param {string} role 角色常量
     * @returns {string}
     */
    function roleBadge(role) {
        var cls = role === 'ROLE_ADMIN' ? 'role-admin' : (role === 'ROLE_WORKER' ? 'role-worker' : 'role-resident');
        return '<span class="badge-soft ' + cls + '">' + (ROLE_TEXT[role] || role) + '</span>';
    }

    /**
     * HTML 转义（所有来自后端的文本在拼接进 innerHTML 前必须过一遍）。
     *
     * @param {*} value 原始值
     * @returns {string}
     */
    function escapeHtml(value) {
        if (value === null || value === undefined) {
            return '';
        }
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    /**
     * 读取当前登录用户（带页面级缓存），未登录自动跳转登录页。
     *
     * @returns {Promise<object>} UserVO
     */
    function loadMe() {
        if (meCache) {
            return Promise.resolve(meCache);
        }
        return get('/api/auth/me', null, { silent: true }).then(function (user) {
            meCache = user;
            return user;
        });
    }

    /**
     * 登出：调用后端销毁会话后回登录页。
     */
    function logout() {
        post('/api/auth/logout', {}, { silent: true }).then(function () {
            window.location.href = '/login';
        }).catch(function () {
            window.location.href = '/login';
        });
    }

    /**
     * 渲染顶栏用户名与头像首字（各页面 head 后调用一次）。
     *
     * @param {object} user UserVO
     */
    function renderUserChip(user) {
        if (!user) {
            return;
        }
        $('[data-user-name]').text(user.realName || user.username || '');
        $('[data-user-role]').text(ROLE_TEXT[user.role] || '');
        $('[data-user-avatar]').text((user.realName || user.username || '?').substring(0, 1));
    }

    /**
     * 生成 datetime-local 输入框所需的最早可选时间（当前时间 + 30 分钟，取整到 5 分钟）。
     *
     * @returns {string} 形如 2026-11-11T13:05
     */
    function minAppointTime() {
        var d = new Date(Date.now() + 30 * 60 * 1000);
        d.setMinutes(Math.ceil(d.getMinutes() / 5) * 5, 0, 0);
        var pad = function (n) {
            return (n < 10 ? '0' : '') + n;
        };
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
            + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    /**
     * 读取 URL 查询参数。
     *
     * @param {string} name 参数名
     * @returns {string|null}
     */
    function query(name) {
        var m = new RegExp('[?&]' + name + '=([^&#]*)').exec(window.location.search);
        return m ? decodeURIComponent(m[1]) : null;
    }

    /**
     * 错误兜底：把异常以 toast 呈现（页面 catch 中统一使用）。
     *
     * @param {Error} err 异常
     */
    function alertError(err) {
        if (err && err.message) {
            toast(err.message, 'error');
        }
    }

    return {
        ROLE_HOME: ROLE_HOME,
        ROLE_TEXT: ROLE_TEXT,
        get: get,
        post: post,
        put: put,
        del: del,
        toast: toast,
        confirm: confirmDialog,
        pager: pager,
        fmtTime: fmtTime,
        fmtDate: fmtDate,
        money: money,
        statusBadge: statusBadge,
        roleBadge: roleBadge,
        escapeHtml: escapeHtml,
        loadMe: loadMe,
        logout: logout,
        renderUserChip: renderUserChip,
        minAppointTime: minAppointTime,
        query: query,
        alertError: alertError
    };
})();
