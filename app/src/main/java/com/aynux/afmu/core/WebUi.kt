package com.aynux.afmu.core

/**
 * The browser front-end, served from the phone so a PC needs nothing installed to transfer
 * files. Kept as one self-contained string: no assets, no CDN, works offline on the LAN.
 *
 * The script below deliberately uses neither `$` identifiers nor template literals — both
 * collide with Kotlin string interpolation inside a raw string and would need escaping on
 * every occurrence. Only [escape]d values are interpolated, and every value that comes from
 * the filesystem reaches the DOM through `textContent`, never `innerHTML`.
 */
object WebUi {

    /**
     * Labels for the served page. The browser UI follows the language chosen in the app
     * rather than the PC browser's Accept-Language: it is the phone's owner who picked it,
     * and the two surfaces reading differently would be confusing.
     *
     * Values are interpolated into HTML attributes and JS string literals, so none of them
     * may contain a single quote or a double quote.
     */
    class Text(zh: Boolean) {
        val htmlLang = if (zh) "zh" else "en"
        val tokenPlaceholder = if (zh) "访问 token" else "access token"
        val accessToken = if (zh) "访问 token" else "Access token"
        val connect = if (zh) "连接" else "Connect"
        val signOut = if (zh) "退出" else "Sign out"
        val upOneFolder = if (zh) "上一级" else "Up one folder"
        val reload = if (zh) "重新加载" else "Reload"
        val newFolder = if (zh) "新建目录" else "New folder"
        val filterFolder = if (zh) "过滤当前目录" else "Filter this folder"
        val filter = if (zh) "过滤" else "Filter"
        val breadcrumb = if (zh) "路径导航" else "Breadcrumb"
        val download = if (zh) "下载" else "Download"
        val delete = if (zh) "删除" else "Delete"
        val clearSelection = if (zh) "取消选择" else "Clear selection"
        val selectAll = if (zh) "全选" else "Select all"
        val colName = if (zh) "名称" else "Name"
        val colSize = if (zh) "大小" else "Size"
        val colModified = if (zh) "修改时间" else "Modified"
        val nothingHere = if (zh) "这里什么都没有。" else "Nothing here."
        val dropFiles = if (zh) "把文件拖到页面任意位置，或者" else "Drop files anywhere on this page, or"
        val browse = if (zh) "浏览" else "browse"
        val overwriteSameName = if (zh) "覆盖同名文件" else "Overwrite files with the same name"
        val enterTokenFirst = if (zh) "请先填写 App 里显示的 token。" else "Enter the token shown in the app."
        val inboxPrefix = if (zh) "收件箱：" else "Inbox: "
        val tokenRejected = if (zh) "token 被拒绝。" else "That token was rejected."
        val signedOut = if (zh) "已退出。" else "Signed out."
        val roots = if (zh) "根目录" else "Roots"
        val uploadsToInbox = if (zh) "上传的文件会落到 App 的收件箱。" else "Uploads land in the app inbox."
        val uploadsGoTo = if (zh) "上传到 " else "Uploads go to "
        val orInboxSuffix = if (zh) "（该目录不可写时落到收件箱）。"
                            else " (or the inbox if that folder is not writable)."
        val selectPrefix = if (zh) "选择 " else "Select "
        val couldNotDelete = if (zh) "删除失败 " else "Could not delete "
        val openFolderFirst = if (zh) "请先进入某个目录 —— 根列表不是真实目录。"
                              else "Open a folder first — the root list is not a real directory."
        val newFolderName = if (zh) "新目录名" else "New folder name"
        val cancel = if (zh) "取消" else "Cancel"
        val retry = if (zh) "重试" else "Retry"
    }

    fun page(deviceName: String, zh: Boolean): String = page(deviceName, Text(zh))

    private fun page(deviceName: String, t: Text): String = """
<!doctype html>
<html lang="${t.htmlLang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>FileBridge · ${escape(deviceName)}</title>
<style>
  :root {
    color-scheme: light dark;
    --bg:#f4f6f8; --fg:#12151a; --card:#fff; --line:#dee3e8; --hover:#f0f3f6;
    --accent:#0b7a55; --on-accent:#fff; --muted:#67707b; --danger:#c62a2f; --warn:#8a5a00;
    --shadow:0 1px 2px rgba(16,24,40,.06), 0 1px 3px rgba(16,24,40,.1);
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg:#0e1113; --fg:#e7eaee; --card:#171b1f; --line:#2a3036; --hover:#1e242a;
      --accent:#4fd1a0; --on-accent:#04150f; --muted:#98a2ad; --danger:#ff6b6b; --warn:#e0b050;
      --shadow:none;
    }
  }
  * { box-sizing: border-box; }
  html, body { height: 100%; }
  body {
    margin:0; background:var(--bg); color:var(--fg);
    font:15px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
    -webkit-text-size-adjust:100%;
  }
  .hidden { display:none !important; }
  .spacer { flex:1 1 auto; }
  .muted { color:var(--muted); font-size:13px; }
  .mono { font-family:ui-monospace, SFMono-Regular, Menlo, monospace; }

  header {
    position:sticky; top:0; z-index:10; display:flex; gap:12px; align-items:center;
    flex-wrap:wrap; padding:12px 20px; background:var(--card);
    border-bottom:1px solid var(--line);
  }
  header h1 { font-size:16px; margin:0; font-weight:650; display:flex; gap:8px; align-items:center; }
  .badge {
    font-size:12px; padding:2px 8px; border-radius:999px;
    border:1px solid var(--line); color:var(--muted); white-space:nowrap;
  }
  .badge.ro { color:var(--warn); border-color:var(--warn); }

  main { max-width:1000px; margin:0 auto; padding:18px 20px 60px; }
  .card {
    background:var(--card); border:1px solid var(--line); border-radius:12px;
    padding:14px; margin-bottom:16px; box-shadow:var(--shadow);
  }

  button, .btn {
    font:inherit; padding:7px 12px; border-radius:8px; border:1px solid var(--line);
    background:var(--card); color:var(--fg); cursor:pointer; line-height:1.2;
  }
  button:hover:not(:disabled) { background:var(--hover); }
  button:disabled { opacity:.45; cursor:default; }
  button.primary { background:var(--accent); border-color:var(--accent); color:var(--on-accent); font-weight:600; }
  button.danger { color:var(--danger); border-color:var(--line); }
  button.icon { padding:6px 10px; }
  button.link { border:none; background:none; padding:2px 4px; color:var(--accent); }
  button.link:hover { text-decoration:underline; background:none; }

  input[type=text], input[type=password], input[type=search] {
    font:inherit; padding:7px 10px; border-radius:8px; border:1px solid var(--line);
    background:var(--bg); color:var(--fg); min-width:0;
  }
  input:focus-visible, button:focus-visible { outline:2px solid var(--accent); outline-offset:1px; }

  .row { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
  .toolbar { display:flex; gap:8px; align-items:center; flex-wrap:wrap; margin-bottom:10px; }
  .toolbar input[type=search] { width:200px; }

  #banner { padding:10px 14px; border-radius:10px; margin-bottom:14px; font-size:14px; }
  #banner.err { background:rgba(198,42,47,.12); color:var(--danger); }
  #banner.ok  { background:rgba(11,122,85,.12); color:var(--accent); }

  .crumbs { display:flex; flex-wrap:wrap; align-items:center; gap:2px; margin-bottom:10px; font-size:13px; }
  .crumbs .sep { color:var(--muted); padding:0 2px; }
  .crumbs .fixed { color:var(--muted); padding:2px 4px; }

  .bulk {
    display:flex; gap:8px; align-items:center; flex-wrap:wrap; margin-bottom:10px;
    padding:8px 10px; border-radius:8px; background:var(--hover); font-size:14px;
  }

  .tablewrap { overflow-x:auto; }
  table { width:100%; border-collapse:collapse; }
  th, td { padding:9px 8px; border-bottom:1px solid var(--line); text-align:left; }
  thead th {
    position:sticky; top:57px; background:var(--card); z-index:1;
    color:var(--muted); font-weight:600; font-size:13px; white-space:nowrap;
  }
  th[data-sort] { cursor:pointer; user-select:none; }
  th[data-sort]:hover { color:var(--fg); }
  th.num, td.num { text-align:right; white-space:nowrap; color:var(--muted); }
  th.pick, td.pick { width:1%; padding-right:0; }
  th.act, td.act { width:1%; text-align:right; white-space:nowrap; }
  tbody tr:hover { background:var(--hover); }
  td.name { word-break:break-word; }
  .entry { display:flex; gap:8px; align-items:baseline; }
  .entry .glyph { flex:0 0 auto; }
  a, .as-link { color:var(--accent); text-decoration:none; cursor:pointer; }
  a:hover, .as-link:hover { text-decoration:underline; }

  #drop {
    border:2px dashed var(--line); border-radius:12px; padding:24px; text-align:center;
    color:var(--muted); transition:border-color .15s, color .15s;
  }
  #drop.hot { border-color:var(--accent); color:var(--fg); background:var(--hover); }
  .qitem { display:flex; gap:10px; align-items:center; margin-top:12px; }
  .qitem .meta { flex:1; min-width:0; }
  .qitem .fname { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .bar { height:6px; background:var(--line); border-radius:3px; overflow:hidden; margin-top:6px; }
  .bar > i { display:block; height:100%; width:0; background:var(--accent); transition:width .15s; }
  .bar.bad > i { background:var(--danger); }
  .qstat { font-size:12px; color:var(--muted); white-space:nowrap; }
  .qstat.bad { color:var(--danger); }

  @media (max-width:640px) {
    header { padding:10px 14px; }
    main { padding:14px 12px 60px; }
    .when { display:none; }
    thead th { top:0; position:static; }
    .toolbar input[type=search] { width:100%; }
  }
</style>
</head>
<body>

<header>
  <h1>📱 <span>${escape(deviceName)}</span></h1>
  <span class="badge hidden" id="rwbadge"></span>
  <span class="spacer"></span>
  <div class="row" id="authbox">
    <input id="token" type="password" placeholder="${t.tokenPlaceholder}" autocomplete="off"
           aria-label="${t.accessToken}" size="14">
    <button id="connect" class="primary">${t.connect}</button>
  </div>
  <div class="row hidden" id="whoami">
    <span class="muted" id="inbox"></span>
    <button id="forget">${t.signOut}</button>
  </div>
</header>

<main>
  <div id="banner" class="hidden" role="status"></div>

  <section class="card">
    <div class="toolbar">
      <button id="up" class="icon" title="${t.upOneFolder}" aria-label="${t.upOneFolder}">↑</button>
      <button id="reload" class="icon" title="${t.reload}" aria-label="${t.reload}">↻</button>
      <button id="mkdir">${t.newFolder}</button>
      <input id="filter" type="search" placeholder="${t.filterFolder}" aria-label="${t.filter}">
      <span class="spacer"></span>
      <span class="muted" id="count"></span>
    </div>

    <nav class="crumbs" id="crumbs" aria-label="${t.breadcrumb}"></nav>

    <div class="bulk hidden" id="bulk">
      <span id="bulkcount"></span>
      <button id="bulkget">${t.download}</button>
      <button id="bulkdel" class="danger">${t.delete}</button>
      <span class="spacer"></span>
      <button id="bulkclear" class="link">${t.clearSelection}</button>
    </div>

    <div class="tablewrap">
      <table>
        <thead>
          <tr>
            <th class="pick"><input type="checkbox" id="all" aria-label="${t.selectAll}"></th>
            <th data-sort="name">${t.colName}</th>
            <th data-sort="size" class="num">${t.colSize}</th>
            <th data-sort="mtime" class="num when">${t.colModified}</th>
            <th class="act"></th>
          </tr>
        </thead>
        <tbody id="rows"></tbody>
      </table>
    </div>
    <p class="muted hidden" id="empty">${t.nothingHere}</p>
  </section>

  <section class="card" id="uploadcard">
    <div id="drop">
      <div>${t.dropFiles}
        <label class="as-link">${t.browse}<input id="picker" type="file" multiple hidden></label>
      </div>
      <div class="muted" id="target" style="margin-top:6px"></div>
      <label class="muted" style="display:inline-flex;gap:6px;align-items:center;margin-top:10px">
        <input type="checkbox" id="overwrite"> ${t.overwriteSameName}
      </label>
    </div>
    <div id="queue"></div>
  </section>
</main>

<script>
'use strict';

var el = function (sel) { return document.querySelector(sel); };
var state = {
  token: localStorage.getItem('afmu-token') || '',
  path: localStorage.getItem('afmu-path') || '/',
  sortKey: localStorage.getItem('afmu-sort') || 'name',
  sortDir: Number(localStorage.getItem('afmu-sortdir') || 1),
  entries: [],
  parent: null,
  filter: '',
  picked: {},
  info: null
};

// ------------------------------------------------------------------ formatting helpers

function fmtSize(n) {
  if (n === null || n === undefined) return '';
  if (n < 1024) return n + ' B';
  var units = ['KB', 'MB', 'GB', 'TB'];
  var value = n / 1024, i = 0;
  while (value >= 1024 && i < units.length - 1) { value /= 1024; i += 1; }
  return value.toFixed(1) + ' ' + units[i];
}

function fmtTime(seconds) {
  if (!seconds) return '';
  var d = new Date(seconds * 1000);
  var pad = function (v) { return (v < 10 ? '0' : '') + v; };
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
    ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function fmtDuration(seconds) {
  if (!isFinite(seconds) || seconds < 0) return '';
  if (seconds < 60) return Math.round(seconds) + 's';
  var m = Math.floor(seconds / 60), s = Math.round(seconds % 60);
  return m + 'm ' + (s < 10 ? '0' : '') + s + 's';
}

function banner(message, kind) {
  var box = el('#banner');
  if (!message) { box.className = 'hidden'; box.textContent = ''; return; }
  box.textContent = message;
  box.className = kind || 'err';
}

// ------------------------------------------------------------------------- HTTP client

/**
 * Builds the query by hand: URLSearchParams encodes a space as '+', but the protocol
 * (PROTOCOL.md 2.3) requires %20 — the server turns a literal '+' back into a plus sign,
 * so anything with a space in its name would 404. encodeURIComponent gets this right.
 */
function apiUrl(endpoint, params) {
  var query = [];
  Object.keys(params || {}).forEach(function (k) {
    var v = params[k];
    if (v === undefined || v === null || v === '') return;
    query.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
  });
  return location.origin + '/api/' + endpoint + (query.length ? '?' + query.join('&') : '');
}

function api(endpoint, params, method) {
  return fetch(apiUrl(endpoint, params), {
    method: method || 'GET',
    headers: { 'X-AFMU-Token': state.token }
  }).then(function (res) {
    return res.json().catch(function () {
      return { ok: false, error: 'HTTP ' + res.status };
    }).then(function (body) {
      if (!res.ok || !body.ok) {
        var err = new Error(body.error || ('HTTP ' + res.status));
        err.status = res.status;
        throw err;
      }
      return body;
    });
  });
}

/**
 * Download links used to carry the token in the query. They no longer can: a credential in
 * a URL ends up in proxy logs, history and Referer, so the API refuses one (PROTOCOL.md §2.5).
 *
 * Instead the server mints a short-lived ticket bound to this one path. We cannot compute
 * the MAC here — crypto.subtle is unavailable over plain HTTP — so we ask for it, then
 * navigate. Minting happens on click rather than while rendering the list, so a directory
 * of 500 files does not fire 500 requests nobody asked for.
 */
function ticketedUrl(entry) {
  return api('ticket', { path: entry.path }).then(function (res) {
    return apiUrl('download', { path: entry.path, ticket: res.ticket });
  });
}

function startDownload(entry) {
  return ticketedUrl(entry).then(function (url) {
    var a = document.createElement('a');
    a.href = url;
    a.download = entry.name;
    document.body.appendChild(a);
    a.click();
    a.remove();
  }).catch(function (err) {
    banner(err.message || 'download failed');
  });
}

// ------------------------------------------------------------------------ session state

function connect() {
  state.token = el('#token').value.trim();
  if (!state.token) { banner('${t.enterTokenFirst}'); return; }
  localStorage.setItem('afmu-token', state.token);
  banner('');
  api('info').then(function (info) {
    state.info = info;
    el('#authbox').classList.add('hidden');
    el('#whoami').classList.remove('hidden');
    el('#inbox').textContent = '${t.inboxPrefix}' + (info.inbox || '');
    var badge = el('#rwbadge');
    badge.classList.remove('hidden');
    badge.textContent = info.writable ? 'read / write' : 'read only';
    badge.className = info.writable ? 'badge' : 'badge ro';
    el('#uploadcard').classList.toggle('hidden', !info.writable);
    el('#mkdir').classList.toggle('hidden', !info.writable);
    return load(state.path);
  }).catch(function (err) {
    signOut(err.status === 401 ? '${t.tokenRejected}' : err.message);
  });
}

function signOut(message) {
  state.info = null;
  state.entries = [];
  state.picked = {};
  localStorage.removeItem('afmu-token');
  el('#authbox').classList.remove('hidden');
  el('#whoami').classList.add('hidden');
  el('#rwbadge').classList.add('hidden');
  el('#rows').textContent = '';
  render();
  banner(message || '${t.signedOut}');
  el('#token').focus();
}

// --------------------------------------------------------------------------- listing

function load(next) {
  if (next !== undefined) {
    state.path = next;
    state.picked = {};
    localStorage.setItem('afmu-path', state.path);
  }
  return api('list', { path: state.path }).then(function (data) {
    state.entries = data.entries || [];
    state.parent = data.parent || null;
    state.path = data.path || state.path;
    localStorage.setItem('afmu-path', state.path);
    banner('');
    render();
  }).catch(function (err) {
    if (err.status === 401) { signOut('${t.tokenRejected}'); return; }
    banner(err.message);
    // Keep the stale listing on screen rather than blanking the page.
  });
}

function visibleEntries() {
  var needle = state.filter.toLowerCase();
  var rows = state.entries.filter(function (e) {
    return !needle || e.name.toLowerCase().indexOf(needle) >= 0;
  });
  var key = state.sortKey, dir = state.sortDir;
  return rows.sort(function (a, b) {
    if (a.dir !== b.dir) return a.dir ? -1 : 1; // folders stay on top of either order
    var diff;
    if (key === 'size') diff = a.size - b.size;
    else if (key === 'mtime') diff = a.mtime - b.mtime;
    else diff = a.name.toLowerCase().localeCompare(b.name.toLowerCase());
    return diff * dir;
  });
}

function renderCrumbs() {
  var box = el('#crumbs');
  box.textContent = '';

  var rootsBtn = document.createElement('button');
  rootsBtn.className = 'link';
  rootsBtn.textContent = '${t.roots}';
  rootsBtn.onclick = function () { load('/'); };
  box.appendChild(rootsBtn);
  if (state.path === '/' || !state.path) return;

  // Only segments at or below a declared root are reachable; the rest are labels.
  var roots = (state.info && state.info.roots) || [];
  var base = '';
  roots.forEach(function (r) {
    if (state.path.indexOf(r.path) === 0 && r.path.length > base.length) base = r.path;
  });

  var parts = state.path.split('/').filter(function (p) { return p.length; });
  var walked = '';
  parts.forEach(function (part) {
    walked += '/' + part;
    var sep = document.createElement('span');
    sep.className = 'sep';
    sep.textContent = '/';
    box.appendChild(sep);

    if (base && walked.length < base.length) {
      var fixed = document.createElement('span');
      fixed.className = 'fixed';
      fixed.textContent = part;
      box.appendChild(fixed);
      return;
    }
    var target = walked;
    var link = document.createElement('button');
    link.className = 'link';
    link.textContent = part;
    link.onclick = function () { load(target); };
    box.appendChild(link);
  });
}

function renderBulk() {
  var chosen = Object.keys(state.picked);
  el('#bulk').classList.toggle('hidden', chosen.length === 0);
  el('#bulkcount').textContent = chosen.length + ' selected';
  var files = chosen.filter(function (p) { return !state.picked[p].dir; });
  el('#bulkget').disabled = files.length === 0;
  el('#bulkdel').disabled = !(state.info && state.info.writable);
}

function render() {
  var rows = visibleEntries();
  var body = el('#rows');
  body.textContent = '';

  el('#count').textContent = rows.length === state.entries.length
    ? state.entries.length + ' items'
    : rows.length + ' of ' + state.entries.length + ' items';
  el('#empty').classList.toggle('hidden', rows.length > 0);
  el('#up').disabled = state.path === '/' || !state.path;
  el('#target').textContent = state.path === '/'
    ? '${t.uploadsToInbox}'
    : '${t.uploadsGoTo}' + state.path + '${t.orInboxSuffix}';

  var writable = !!(state.info && state.info.writable);

  rows.forEach(function (entry) {
    var tr = document.createElement('tr');

    var pick = document.createElement('td');
    pick.className = 'pick';
    var box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = !!state.picked[entry.path];
    box.setAttribute('aria-label', '${t.selectPrefix}' + entry.name);
    box.onchange = function () {
      if (box.checked) state.picked[entry.path] = entry; else delete state.picked[entry.path];
      renderBulk();
      syncSelectAll();
    };
    pick.appendChild(box);
    tr.appendChild(pick);

    var name = document.createElement('td');
    name.className = 'name';
    var wrap = document.createElement('span');
    wrap.className = 'entry';
    var glyph = document.createElement('span');
    glyph.className = 'glyph';
    glyph.textContent = entry.dir ? '📁' : '📄';
    wrap.appendChild(glyph);

    var label;
    if (entry.dir) {
      label = document.createElement('span');
      label.className = 'as-link';
      label.tabIndex = 0;
      label.textContent = entry.name;
      label.onclick = function () { load(entry.path); };
      label.onkeydown = function (ev) {
        if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); load(entry.path); }
      };
    } else {
      // href stays a placeholder: the real URL needs a ticket, and that is one round trip
      // we only want to spend when the user actually clicks.
      label = document.createElement('a');
      label.href = '#';
      label.textContent = entry.name;
      label.onclick = function (ev) { ev.preventDefault(); startDownload(entry); };
    }
    wrap.appendChild(label);
    name.appendChild(wrap);
    tr.appendChild(name);

    var size = document.createElement('td');
    size.className = 'num';
    size.textContent = entry.dir ? '—' : fmtSize(entry.size);
    tr.appendChild(size);

    var when = document.createElement('td');
    when.className = 'num when';
    when.textContent = fmtTime(entry.mtime);
    tr.appendChild(when);

    var act = document.createElement('td');
    act.className = 'act';
    if (writable) {
      var del = document.createElement('button');
      del.className = 'link danger';
      del.textContent = 'Delete';
      del.onclick = function () { removeEntry(entry); };
      act.appendChild(del);
    }
    tr.appendChild(act);

    body.appendChild(tr);
  });

  renderCrumbs();
  renderBulk();
  syncSelectAll();
  markSortHeader();
}

function markSortHeader() {
  document.querySelectorAll('th[data-sort]').forEach(function (th) {
    var base = th.textContent.replace(/ [▲▼]/, '');
    th.textContent = th.dataset.sort === state.sortKey
      ? base + (state.sortDir > 0 ? ' ▲' : ' ▼')
      : base;
  });
}

function syncSelectAll() {
  var rows = visibleEntries();
  var chosen = rows.filter(function (e) { return state.picked[e.path]; }).length;
  var all = el('#all');
  all.checked = rows.length > 0 && chosen === rows.length;
  all.indeterminate = chosen > 0 && chosen < rows.length;
}

// ---------------------------------------------------------------------------- actions

function removeEntry(entry) {
  var question = entry.dir
    ? 'Delete the folder ' + entry.name + ' and everything inside it? This cannot be undone.'
    : 'Delete ' + entry.name + '?';
  if (!confirm(question)) return;
  api('delete', { path: entry.path, recursive: entry.dir ? '1' : '' }, 'POST')
    .then(function () { load(); })
    .catch(function (err) { banner(err.message); });
}

function deleteSelected() {
  var chosen = Object.keys(state.picked).map(function (p) { return state.picked[p]; });
  if (!chosen.length) return;
  if (!confirm('Delete ' + chosen.length + ' item(s)? Folders are removed with their contents. This cannot be undone.')) return;

  var run = chosen.reduce(function (chain, entry) {
    return chain.then(function () {
      return api('delete', { path: entry.path, recursive: entry.dir ? '1' : '' }, 'POST')
        .catch(function (err) { banner('${t.couldNotDelete}' + entry.name + ': ' + err.message); });
    });
  }, Promise.resolve());

  run.then(function () { state.picked = {}; load(); });
}

/** Browsers only allow one automatic download at a time, so space the clicks out. */
function downloadSelected() {
  var files = Object.keys(state.picked)
    .map(function (p) { return state.picked[p]; })
    .filter(function (e) { return !e.dir; });

  files.forEach(function (entry, i) {
    setTimeout(function () { startDownload(entry); }, i * 350);
  });
  if (files.length > 1) {
    banner(files.length + ' downloads queued — allow multiple downloads if the browser asks.', 'ok');
  }
}

function makeFolder() {
  if (state.path === '/') { banner('${t.openFolderFirst}'); return; }
  var name = prompt('${t.newFolderName}');
  if (!name) return;
  api('mkdir', { path: state.path, name: name }, 'POST')
    .then(function () { load(); })
    .catch(function (err) { banner(err.message); });
}

// ----------------------------------------------------------------------- upload queue

var queue = [];
var active = null;

function enqueue(file) {
  var item = {
    file: file,
    row: document.createElement('div'),
    fill: null,
    stat: null,
    xhr: null,
    done: false
  };
  item.row.className = 'qitem';

  var meta = document.createElement('div');
  meta.className = 'meta';
  var title = document.createElement('span');
  title.className = 'fname';
  title.textContent = file.name + '  ·  ' + fmtSize(file.size);
  var bar = document.createElement('div');
  bar.className = 'bar';
  item.fill = document.createElement('i');
  bar.appendChild(item.fill);
  meta.appendChild(title);
  meta.appendChild(bar);

  item.stat = document.createElement('span');
  item.stat.className = 'qstat';
  item.stat.textContent = 'queued';

  var cancel = document.createElement('button');
  cancel.className = 'link';
  cancel.textContent = '${t.cancel}';
  cancel.onclick = function () {
    if (item.xhr) item.xhr.abort();
    else { queue.splice(queue.indexOf(item), 1); item.row.remove(); }
  };
  item.cancel = cancel;

  item.row.appendChild(meta);
  item.row.appendChild(item.stat);
  item.row.appendChild(cancel);
  el('#queue').appendChild(item.row);

  queue.push(item);
  pump();
}

/** One upload at a time: a phone writing to flash gains nothing from parallel streams. */
function pump() {
  if (active || !queue.length) return;
  active = queue.shift();
  send(active);
}

function finish(item) {
  item.done = true;
  item.cancel.remove();
  if (active === item) active = null;
  if (!queue.length && !active) load();
  pump();
}

function send(item) {
  var params = { name: item.file.name };
  if (state.path && state.path !== '/') params.dir = state.path;
  if (el('#overwrite').checked) params.overwrite = '1';

  var xhr = new XMLHttpRequest();
  item.xhr = xhr;
  xhr.open('POST', apiUrl('upload', params));
  xhr.setRequestHeader('X-AFMU-Token', state.token);
  xhr.setRequestHeader('Content-Type', 'application/octet-stream');

  var started = Date.now();
  xhr.upload.onprogress = function (ev) {
    if (!ev.lengthComputable) { item.stat.textContent = fmtSize(ev.loaded); return; }
    var ratio = ev.loaded / ev.total;
    item.fill.style.width = (100 * ratio) + '%';
    var elapsed = (Date.now() - started) / 1000;
    var speed = elapsed > 0 ? ev.loaded / elapsed : 0;
    var left = speed > 0 ? (ev.total - ev.loaded) / speed : Infinity;
    item.stat.textContent = Math.round(100 * ratio) + '%  ·  ' + fmtSize(Math.round(speed)) +
      '/s  ·  ' + fmtDuration(left);
  };

  xhr.onload = function () {
    var body = null;
    try { body = JSON.parse(xhr.responseText); } catch (e) { body = null; }
    if (xhr.status === 200 && body && body.ok) {
      item.fill.style.width = '100%';
      var saved = (body.saved && body.saved[0]) || item.file.name;
      item.stat.textContent = 'saved';
      item.stat.title = saved;
    } else {
      fail(item, (body && body.error) || ('HTTP ' + xhr.status));
    }
    finish(item);
  };

  xhr.onerror = function () { fail(item, 'network error'); finish(item); };
  xhr.onabort = function () { fail(item, 'cancelled'); finish(item); };

  item.stat.textContent = 'sending…';
  xhr.send(item.file);
}

function fail(item, reason) {
  item.fill.parentNode.className = 'bar bad';
  item.fill.style.width = '100%';
  item.stat.className = 'qstat bad';
  item.stat.textContent = reason;

  var retry = document.createElement('button');
  retry.className = 'link';
  retry.textContent = '${t.retry}';
  retry.onclick = function () { item.row.remove(); enqueue(item.file); };
  item.row.appendChild(retry);
}

// ------------------------------------------------------------------------------ wiring

el('#connect').onclick = connect;
el('#token').onkeydown = function (ev) { if (ev.key === 'Enter') connect(); };
el('#forget').onclick = function () { signOut(); };
el('#reload').onclick = function () { load(); };
el('#up').onclick = function () { load(state.parent || '/'); };
el('#mkdir').onclick = makeFolder;
el('#bulkget').onclick = downloadSelected;
el('#bulkdel').onclick = deleteSelected;
el('#bulkclear').onclick = function () { state.picked = {}; render(); };

el('#filter').oninput = function (ev) { state.filter = ev.target.value; render(); };

el('#all').onchange = function (ev) {
  visibleEntries().forEach(function (entry) {
    if (ev.target.checked) state.picked[entry.path] = entry; else delete state.picked[entry.path];
  });
  render();
};

document.querySelectorAll('th[data-sort]').forEach(function (th) {
  th.onclick = function () {
    var key = th.dataset.sort;
    state.sortDir = state.sortKey === key ? -state.sortDir : 1;
    state.sortKey = key;
    localStorage.setItem('afmu-sort', state.sortKey);
    localStorage.setItem('afmu-sortdir', String(state.sortDir));
    render();
  };
});

el('#picker').onchange = function (ev) {
  Array.prototype.slice.call(ev.target.files).forEach(enqueue);
  ev.target.value = '';
};

// Dropping is accepted anywhere on the page; the dashed box is only the visual hint.
var drop = el('#drop');
['dragenter', 'dragover'].forEach(function (type) {
  document.addEventListener(type, function (ev) {
    if (!state.info || !state.info.writable) return;
    ev.preventDefault();
    drop.classList.add('hot');
  });
});
['dragleave', 'dragend'].forEach(function (type) {
  document.addEventListener(type, function (ev) {
    if (ev.relatedTarget === null) drop.classList.remove('hot');
  });
});
document.addEventListener('drop', function (ev) {
  ev.preventDefault();
  drop.classList.remove('hot');
  if (!state.info || !state.info.writable) return;
  Array.prototype.slice.call(ev.dataTransfer.files).forEach(enqueue);
});

document.addEventListener('keydown', function (ev) {
  var focused = document.activeElement || document.body;
  var typing = /^(INPUT|TEXTAREA)${'$'}/.test(focused.tagName || '');
  if (ev.key === '/' && !typing) { ev.preventDefault(); el('#filter').focus(); }
  if (ev.key === 'Escape' && document.activeElement === el('#filter')) {
    el('#filter').value = '';
    state.filter = '';
    render();
    el('#filter').blur();
  }
});

el('#token').value = state.token;
if (state.token) connect(); else el('#token').focus();
</script>
</body>
</html>
""".trimIndent()

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
