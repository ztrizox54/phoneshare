'use strict';

// ============================================================
//  STATE
// ============================================================
let currentPage = 'files';
let currentPath = '/';
let ws = null;
let wsRetry = 0;
let authed = false;
let authPollAbort = null;
let me = null;                    // /api/me payload
let lastSearch = { q: '', deep: false };
let searchTimer = null;
const activeUploads = new Map();  // id -> { xhr, name }

// ============================================================
//  DOM helpers
// ============================================================
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"']/g, c =>
    ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

function fmtSize(b) {
    if (b == null || b < 0) return '';
    if (b < 1024) return b + ' B';
    const u = ['KB','MB','GB','TB']; let v = b/1024, i = 0;
    while (v >= 1024 && i < u.length-1) { v /= 1024; i++; }
    return v.toFixed(v < 10 ? 1 : 0) + ' ' + u[i];
}

function fmtDate(ms) {
    if (!ms) return '';
    const d = new Date(ms);
    const pad = (n) => n.toString().padStart(2,'0');
    return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fmtTime(ms) {
    if (!ms) return '';
    const d = new Date(ms);
    const pad = (n) => n.toString().padStart(2,'0');
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function fmtETA(seconds) {
    if (!isFinite(seconds) || seconds < 0) return '—';
    if (seconds < 1) return '<1s';
    if (seconds < 60) return Math.round(seconds) + 's';
    const m = Math.floor(seconds / 60), s = Math.round(seconds % 60);
    return `${m}m ${s}s`;
}

function fmtSpeed(bps) {
    if (!isFinite(bps) || bps <= 0) return '';
    return fmtSize(bps) + '/s';
}

const _TEXT_EXTS = new Set([
    'txt','md','markdown','log','json','xml','html','htm','css','scss','less',
    'js','mjs','cjs','ts','tsx','jsx','kt','kts','java','c','cc','cpp','h','hpp',
    'py','rb','rs','go','sh','bash','zsh','ps1','bat','cmd',
    'yml','yaml','toml','ini','cfg','conf','env','properties','gradle',
    'csv','tsv','srt','sub','sql','rtf','svg','gitignore','dockerfile',
    'pl','php','lua','swift','dart','vue','astro','jsonc','xaml',
]);
function isTextLike(name) {
    const lower = (name || '').toLowerCase();
    // Files with no extension but a leading dot (e.g. .gitignore, .env) — match by basename.
    if (lower.startsWith('.')) return _TEXT_EXTS.has(lower.slice(1));
    const dot = lower.lastIndexOf('.');
    if (dot < 0) return false;
    return _TEXT_EXTS.has(lower.slice(dot + 1));
}

// ============================================================
//  ROUTING — #/files/<path>, #/devices, #/logs, #/settings
// ============================================================
function parseRoute() {
    const h = location.hash || '#/files/';
    let raw = h.startsWith('#') ? h.slice(1) : h;
    try { raw = decodeURIComponent(raw); } catch (_) {}
    if (!raw.startsWith('/')) raw = '/' + raw;
    const parts = raw.split('/').filter(Boolean);
    let page = 'files';
    let path = '/';
    if (parts.length === 0) { page = 'files'; path = '/'; }
    else if (parts[0] === 'notes')    page = 'notes';
    else if (parts[0] === 'settings') page = 'settings';
    else if (parts[0] === 'files') {
        page = 'files';
        path = '/' + parts.slice(1).join('/');
    } else if (parts[0] === 'devices' || parts[0] === 'logs') {
        // Legacy routes — those pages were moved to the phone. Send to Files.
        page = 'files';
        path = '/';
    } else {
        page = 'files';
        path = '/' + parts.join('/');
    }
    if (path === '/') path = '/';
    return { page, path };
}

function navigateFiles(path) {
    const target = '#/files' + (path.startsWith('/') ? path : '/' + path);
    if (location.hash !== target) location.hash = target;
    else loadCurrentPath();
}

function navigatePage(page) {
    if (page === 'files') location.hash = '#/files' + (currentPath || '/');
    else location.hash = '#/' + page;
}

window.addEventListener('hashchange', applyRoute);

function applyRoute() {
    const r = parseRoute();
    currentPage = r.page;
    currentPath = r.path;
    showPage(r.page);
    if (!authed) {
        // Don't render a half-loaded page — kick off auth probe.
        ensureAuthThenApply();
        return;
    }
    if (r.page === 'files')      loadCurrentPath();
    else if (r.page === 'notes') loadNotesPage();
}

let _ensuringAuth = false;
async function ensureAuthThenApply() {
    if (_ensuringAuth) return;
    _ensuringAuth = true;
    try {
        const r = await fetch('/api/me');
        if (r.ok) {
            authed = true;
            me = await r.json();
            const pill = $('mePill');
            pill.hidden = false;
            $('meRole').textContent = me.role;
            $('meRole').className = 'me-role ' + me.role;
            $('meLabel').textContent = me.label;
            renderGuestJump();
            revealApp();
            applyRoute();
            connectWs();
        } else if (r.status === 401) {
            authed = false;
            startAuthFlow();
        } else {
            // Some other error — show splash with message
            setSplashStatus('error: HTTP ' + r.status, 'error');
            setTimeout(ensureAuthThenApply, 3000);
            return;
        }
    } catch (e) {
        setSplashStatus('Network error — retrying…', 'error');
        setTimeout(() => { _ensuringAuth = false; ensureAuthThenApply(); }, 3000);
        return;
    } finally {
        _ensuringAuth = false;
    }
}

function showPage(page) {
    document.querySelectorAll('.page').forEach(el => {
        el.hidden = el.dataset.page !== page;
    });
    document.querySelectorAll('.nav-item').forEach(el => {
        el.classList.toggle('active', el.dataset.page === page);
    });
    if (window.innerWidth <= 820) {
        $('sidebar').classList.remove('open');
    }
    // If we're leaving the notes page with an autosave still pending, flush.
    if (page !== 'notes' && notesAutoSave) {
        clearTimeout(notesAutoSave);
        notesAutoSave = null;
        if (notesDirty) saveNotes();
    }
}

// ============================================================
//  APP REVEAL / SPLASH
// ============================================================
function revealApp() {
    const splash = $('splash');
    const app = $('app');
    if (app && app.hidden) app.hidden = false;
    if (splash && !splash.hidden) {
        splash.classList.add('fade-out');
        setTimeout(() => { splash.hidden = true; splash.classList.remove('fade-out'); }, 240);
    }
}

function setSplashStatus(text, kind) {
    const el = $('splashStatus');
    if (!el) return;
    el.textContent = text;
    el.classList.remove('error');
    if (kind) el.classList.add(kind);
}

// ============================================================
//  ICONS
// ============================================================
const ICON_FOLDER = `
<svg class="ico ico-folder" viewBox="0 0 24 24" aria-hidden="true">
  <path d="M3 6.5A1.5 1.5 0 0 1 4.5 5h4.379a1.5 1.5 0 0 1 1.06.44l1.122 1.12A1.5 1.5 0 0 0 12.121 7H19.5A1.5 1.5 0 0 1 21 8.5V9H3V6.5Z" fill="#e0a93a"/>
  <path d="M3 9h18v8.5A1.5 1.5 0 0 1 19.5 19h-15A1.5 1.5 0 0 1 3 17.5V9Z" fill="#f3c14a"/>
  <path d="M3 9h18v1.2H3V9Z" fill="#fff" fill-opacity=".18"/>
</svg>`;

const ICON_FILE = `
<svg class="ico ico-file" viewBox="0 0 24 24" aria-hidden="true">
  <path d="M6 3.5A1.5 1.5 0 0 1 7.5 2h6.379a1.5 1.5 0 0 1 1.06.44l3.621 3.62A1.5 1.5 0 0 1 19 7.122V20.5A1.5 1.5 0 0 1 17.5 22h-10A1.5 1.5 0 0 1 6 20.5v-17Z" fill="#3a4258" stroke="#566079" stroke-width="1"/>
  <path d="M14 2.25v3.5A1.25 1.25 0 0 0 15.25 7h3.5" fill="none" stroke="#8aa0c8" stroke-width="1"/>
  <path d="M9 12h7M9 15h7M9 18h4.5" stroke="#8aa0c8" stroke-width="1.2" stroke-linecap="round"/>
</svg>`;

const ICON_UP = `
<svg class="ico ico-up" viewBox="0 0 24 24" aria-hidden="true">
  <path d="M11 5l-6 6h4v7h4v-7h4l-6-6Z" fill="#8aa3ff"/>
</svg>`;

// ============================================================
//  FILES PAGE
// ============================================================
async function loadCurrentPath() {
    if (currentPage !== 'files') return;
    // Guest can't see arbitrary paths — auto-jump to their first allowed root
    // when they land somewhere they don't have access to (e.g. default "/").
    if (me && me.role === 'guest' && me.allowedRoots && me.allowedRoots.length > 0) {
        const inAllowed = me.allowedRoots.some(
            r => currentPath === r || currentPath.startsWith(r.replace(/\/$/, '') + '/')
        );
        if (!inAllowed) {
            navigateFiles(me.allowedRoots[0]);
            return;
        }
    }
    renderBreadcrumb(currentPath);
    if (authed) setBody([{ empty: 'Loading…' }]);
    try {
        const res = await fetch('/api/list?path=' + encodeURIComponent(currentPath));
        if (res.status === 401) {
            authed = false;
            startAuthFlow();
            return;
        }
        if (res.status === 403 && me && me.role === 'guest' && me.allowedRoots && me.allowedRoots.length > 0) {
            // Server says we can't see this path; jump to a safe one.
            navigateFiles(me.allowedRoots[0]);
            return;
        }
        if (!res.ok) {
            revealApp();
            const j = await res.json().catch(() => ({}));
            setBody([{ error: j.error || ('HTTP ' + res.status) }]);
            return;
        }
        const wasAuthed = authed;
        authed = true;
        const data = await res.json();
        revealApp();
        renderList(data);
        if (!wasAuthed) { loadDeviceName(); loadMe(); }
    } catch (e) {
        revealApp();
        setBody([{ error: e.message || 'Network error' }]);
    }
}

function renderBreadcrumb(path) {
    const bc = $('breadcrumb');
    const parts = path.split('/').filter(Boolean);
    bc.innerHTML = '';

    // For guests with allowed roots, replace the leading "/" with a dropdown of allowed roots.
    const isGuestWithRoots = me && me.role === 'guest' && me.allowedRoots && me.allowedRoots.length > 0;
    if (isGuestWithRoots) {
        const sel = document.createElement('select');
        sel.className = 'crumb-root-select';
        for (const r of me.allowedRoots) {
            const opt = document.createElement('option');
            opt.value = r;
            opt.textContent = r;
            sel.appendChild(opt);
        }
        // Pick the option whose value is a prefix of the current path
        const matched = me.allowedRoots.find(r => path === r || path.startsWith(r + '/')) || me.allowedRoots[0];
        sel.value = matched;
        sel.onchange = () => navigateFiles(sel.value);
        bc.appendChild(sel);
        // Render path tail relative to the matched root
        const rootParts = matched.split('/').filter(Boolean);
        const tail = parts.slice(rootParts.length);
        let acc = matched;
        tail.forEach((p) => {
            acc += '/' + p;
            const sep = document.createElement('span');
            sep.className = 'sep'; sep.textContent = '/';
            bc.appendChild(sep);
            const seg = document.createElement('span');
            seg.className = 'seg'; seg.textContent = p;
            const target = acc;
            seg.onclick = () => navigateFiles(target);
            bc.appendChild(seg);
        });
        return;
    }

    const root = document.createElement('span');
    root.className = 'seg';
    root.textContent = '/';
    root.onclick = () => navigateFiles('/');
    bc.appendChild(root);
    let acc = '';
    parts.forEach((p, idx) => {
        acc += '/' + p;
        const sep = document.createElement('span');
        sep.className = 'sep';
        sep.textContent = idx === 0 ? '' : '/';
        bc.appendChild(sep);
        const seg = document.createElement('span');
        seg.className = 'seg';
        seg.textContent = p;
        const target = acc;
        seg.onclick = () => navigateFiles(target);
        bc.appendChild(seg);
    });
}

function applySort(entries) {
    const sortBy = $('sortSelect').value;
    const arr = entries.slice();
    arr.sort((a, b) => {
        if (a.dir !== b.dir) return a.dir ? -1 : 1; // folders first
        switch (sortBy) {
            case 'name-asc':  return a.name.localeCompare(b.name);
            case 'name-desc': return b.name.localeCompare(a.name);
            case 'date-asc':  return (a.mtime||0) - (b.mtime||0);
            case 'date-desc': return (b.mtime||0) - (a.mtime||0);
            case 'size-asc':  return (a.size||0)  - (b.size||0);
            case 'size-desc': return (b.size||0)  - (a.size||0);
            default: return a.name.localeCompare(b.name);
        }
    });
    return arr;
}

function renderList(data) {
    const tbody = $('fileBody');
    tbody.innerHTML = '';
    if (data.parent != null) {
        const tr = document.createElement('tr');
        tr.className = 'file-row dir parent';
        tr.innerHTML = `
            <td class="col-name"><a href="#" class="name-link">${ICON_UP}<span class="name">..</span></a></td>
            <td class="col-size"></td>
            <td class="col-date"></td>
            <td class="col-act"></td>`;
        tr.querySelector('.name-link').onclick = (e) => { e.preventDefault(); navigateFiles(data.parent); };
        tbody.appendChild(tr);
    }
    if (!data.entries || data.entries.length === 0) {
        if (data.parent == null) {
            if (me && me.role === 'guest' && me.allowedRoots && me.allowedRoots.length > 0) {
                setBody([{ empty: 'Pick one of your allowed folders from the dropdown above.' }]);
            } else if (me && me.role === 'guest') {
                setBody([{ empty: 'No folders are shared with this device. Ask the phone owner to grant access in Devices.' }]);
            } else {
                setBody([{ empty: '(empty folder)' }]);
            }
        }
        return;
    }
    const sorted = applySort(data.entries);
    for (const f of sorted) tbody.appendChild(buildRow(f, false));
}

function renderSearchResults(data) {
    const tbody = $('fileBody');
    tbody.innerHTML = '';
    if (!data.results || data.results.length === 0) {
        setBody([{ empty: 'No matches.' }]);
        return;
    }
    const sorted = applySort(data.results);
    for (const f of sorted) tbody.appendChild(buildRow(f, true));
    if (data.truncated) {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td colspan="4" class="empty">(results truncated — refine your query)</td>`;
        tbody.appendChild(tr);
    }
}

function buildRow(f, showPath) {
    const tr = document.createElement('tr');
    tr.className = 'file-row' + (f.dir ? ' dir' : '');

    const tdName = document.createElement('td');
    tdName.className = 'col-name';
    const link = document.createElement('a');
    link.className = 'name-link';
    const iconWrap = document.createElement('span');
    iconWrap.className = 'icon';
    iconWrap.innerHTML = f.dir ? ICON_FOLDER : ICON_FILE;
    const nameSpan = document.createElement('span');
    nameSpan.className = 'name';
    nameSpan.textContent = f.name;
    link.appendChild(iconWrap);
    link.appendChild(nameSpan);
    if (f.match && f.match !== 'name') {
        const tag = document.createElement('span');
        tag.className = 'match-tag';
        tag.textContent = f.match.startsWith('zip:') ? 'zip' : 'content';
        tag.title = f.match;
        link.appendChild(tag);
    }
    if (f.dir) {
        link.href = '#/files' + f.path;
        link.onclick = (e) => { e.preventDefault(); navigateFiles(f.path); };
    } else if (isTextLike(f.name)) {
        link.href = '#';
        link.onclick = (e) => { e.preventDefault(); requestView(f.path, f.name, f.size); };
    } else {
        link.href = '#';
        link.onclick = (e) => { e.preventDefault(); requestDownload(f.path, f.name, f.size); };
    }
    tdName.appendChild(link);
    if (showPath) {
        const hint = document.createElement('span');
        hint.className = 'path-hint';
        hint.textContent = f.path;
        tdName.appendChild(hint);
    }

    const tdSize = document.createElement('td');
    tdSize.className = 'col-size';
    tdSize.textContent = f.dir ? '' : fmtSize(f.size);

    const tdDate = document.createElement('td');
    tdDate.className = 'col-date';
    tdDate.textContent = fmtDate(f.mtime);

    const tdAct = document.createElement('td');
    tdAct.className = 'col-act';
    const more = document.createElement('button');
    more.className = 'act-more';
    more.title = 'More actions';
    more.textContent = '⋯';
    more.onclick = (e) => { e.preventDefault(); e.stopPropagation(); openRowMenu(more, f); };
    tdAct.appendChild(more);

    tr.appendChild(tdName);
    tr.appendChild(tdSize);
    tr.appendChild(tdDate);
    tr.appendChild(tdAct);
    return tr;
}

async function loadDeviceName() {
    try {
        const r = await fetch('/api/info');
        if (!r.ok) return;
        const j = await r.json();
        if (j && j.device) $('brandDevice').textContent = j.device;
    } catch (_) { /* ignore */ }
}

function showBanner(message, kind, sub) {
    const stack = $('bannerStack');
    if (!stack) return;
    const el = document.createElement('div');
    el.className = 'banner' + (kind ? ' ' + kind : '');
    const icon = kind === 'danger' ? '⚠' : kind === 'warn' ? '⚠' : 'ℹ';
    el.innerHTML = `
        <div class="banner-ic">${icon}</div>
        <div class="banner-body">${esc(message)}${sub ? `<small>${esc(sub)}</small>` : ''}</div>
        <button class="banner-close" title="Dismiss">×</button>`;
    el.querySelector('.banner-close').onclick = () => dismissBanner(el);
    stack.appendChild(el);
    setTimeout(() => dismissBanner(el), 8000);
}
function dismissBanner(el) {
    if (!el || !el.parentNode) return;
    el.classList.add('fade-out');
    setTimeout(() => { try { el.remove(); } catch (_) {} }, 220);
}

function diffMe(oldMe, newMe) {
    if (!oldMe || !newMe) return null;
    const changes = [];
    if (oldMe.role !== newMe.role) {
        changes.push({ msg: `Your role is now ${newMe.role.toUpperCase()}`,
                       sub: `was ${oldMe.role}`, kind: newMe.role === 'guest' ? 'warn' : 'info' });
    }
    const oldR = (oldMe.allowedRoots || []).join(',');
    const newR = (newMe.allowedRoots || []).join(',');
    if (oldR !== newR) {
        const added = (newMe.allowedRoots || []).filter(x => !(oldMe.allowedRoots || []).includes(x));
        const removed = (oldMe.allowedRoots || []).filter(x => !(newMe.allowedRoots || []).includes(x));
        const parts = [];
        if (added.length)   parts.push('+ ' + added.join(', '));
        if (removed.length) parts.push('− ' + removed.join(', '));
        changes.push({ msg: 'Allowed folders changed', sub: parts.join('   '), kind: 'info' });
    }
    const flags = [
        ['requireDownloadApproval', 'Downloads'],
        ['requireUploadApproval',   'Uploads'],
        ['requireDeleteApproval',   'Deletions'],
        ['requireRenameApproval',   'Renames'],
    ];
    for (const [k, label] of flags) {
        if (oldMe[k] !== newMe[k]) {
            changes.push({
                msg: `${label} now ${newMe[k] ? 'require' : 'do NOT require'} approval`,
                kind: newMe[k] ? 'warn' : 'info',
            });
        }
    }
    return changes.length ? changes : null;
}

async function loadMe() {
    const prevMe = me;
    try {
        const r = await fetch('/api/me');
        if (!r.ok) { me = null; renderGuestJump(); return; }
        me = await r.json();
        const changes = diffMe(prevMe, me);
        if (changes) for (const c of changes) showBanner(c.msg, c.kind, c.sub);
        const pill = $('mePill');
        pill.hidden = false;
        $('meRole').textContent = me.role;
        $('meRole').className = 'me-role ' + me.role;
        $('meLabel').textContent = me.label;
        renderGuestJump();
        // If guest landed on a disallowed path before me was loaded, redirect now.
        if (me.role === 'guest' && me.allowedRoots && me.allowedRoots.length > 0
            && currentPage === 'files') {
            const inAllowed = me.allowedRoots.some(
                r => currentPath === r || currentPath.startsWith(r.replace(/\/$/, '') + '/')
            );
            if (!inAllowed) navigateFiles(me.allowedRoots[0]);
        }
    } catch (_) { me = null; renderGuestJump(); }
}

function renderGuestJump() {
    // Replaced by inline breadcrumb dropdown — re-render the breadcrumb if on Files.
    if (currentPage === 'files') renderBreadcrumb(currentPath);
}

function setBody(rows) {
    const tbody = $('fileBody');
    tbody.innerHTML = '';
    for (const r of rows) {
        const tr = document.createElement('tr');
        if (r.error) {
            tr.innerHTML = `<td colspan="4" class="error-row">${esc(r.error)}</td>`;
        } else {
            tr.innerHTML = `<td colspan="4" class="empty">${esc(r.empty || '')}</td>`;
        }
        tbody.appendChild(tr);
    }
}

// ============================================================
//  SEARCH
// ============================================================
function onSearchInput() {
    if (searchTimer) clearTimeout(searchTimer);
    const q = $('searchInput').value.trim();
    const deep = $('searchDeep').checked;
    if (!q) {
        lastSearch = { q: '', deep: false };
        loadCurrentPath();
        return;
    }
    searchTimer = setTimeout(() => doSearch(q, deep), 280);
}

async function doSearch(q, deep) {
    lastSearch = { q, deep };
    setBody([{ empty: 'Searching…' }]);
    try {
        const url = '/api/search?path=' + encodeURIComponent(currentPath)
            + '&q=' + encodeURIComponent(q)
            + (deep ? '&deep=1' : '');
        const r = await fetch(url);
        if (!r.ok) {
            const j = await r.json().catch(() => ({}));
            setBody([{ error: j.error || ('HTTP ' + r.status) }]);
            return;
        }
        renderSearchResults(await r.json());
    } catch (e) {
        setBody([{ error: e.message || 'Search failed' }]);
    }
}

// ============================================================
//  DOWNLOAD (with optional approval gate)
// ============================================================
async function requestDownload(path, name, size) {
    if (!me || !me.requireDownloadApproval) {
        triggerDownload('/download?path=' + encodeURIComponent(path), null);
        return;
    }
    const fd = new FormData();
    fd.append('action', 'download');
    fd.append('path', path);
    if (size != null) fd.append('size', String(size));
    showApprovalToast('Asking phone owner for permission…', name);
    let res;
    try {
        const r = await fetch('/api/approvals/request', { method: 'POST', body: fd });
        res = await r.json();
        if (!r.ok) throw new Error(res.error || ('HTTP ' + r.status));
    } catch (e) {
        hideApprovalToast();
        alert('Approval request failed: ' + e.message);
        return;
    }
    if (!res.required) {
        hideApprovalToast();
        triggerDownload('/download?path=' + encodeURIComponent(path), null);
        return;
    }
    const decision = await pollApproval(res.id);
    hideApprovalToast();
    if (decision === 'approved') {
        triggerDownload('/download?path=' + encodeURIComponent(path)
            + '&approval=' + encodeURIComponent(res.id), null);
    } else if (decision === 'denied') {
        alert('Download denied by phone owner.');
    } else {
        alert('Approval timed out.');
    }
}

function triggerDownload(url, filename) {
    // Use a transient <a download> click instead of location.href so the browser
    // doesn't treat this as a navigation (the navigation is what was tearing
    // down the WS and causing the "live → offline" flicker on every download).
    const a = document.createElement('a');
    a.href = url;
    if (filename) a.download = filename;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { try { a.remove(); } catch (_) {} }, 200);
}

// ============================================================
//  ROW ACTION MENU (...)
// ============================================================
let _rowMenuEl = null;
function openRowMenu(anchor, f) {
    closeRowMenu();
    const menu = document.createElement('div');
    menu.className = 'row-menu';
    const items = [];
    if (!f.dir) items.push({ label: '👁  View',     act: () => requestView(f.path, f.name, f.size) });
    if (!f.dir) items.push({ label: '⬇  Download', act: () => requestDownload(f.path, f.name, f.size) });
    items.push({ label: '✎  Rename',           act: () => requestRename(f.path, f.name) });
    items.push({ label: '↪  Move…',            act: () => requestMove(f.path, f.name) });
    items.push({ label: '🗑  Delete',          act: () => requestDelete(f.path, f.name, f.dir), danger: true });
    for (const it of items) {
        const b = document.createElement('button');
        b.className = 'row-menu-item' + (it.danger ? ' danger' : '');
        b.textContent = it.label;
        b.onclick = (e) => { e.preventDefault(); closeRowMenu(); it.act(); };
        menu.appendChild(b);
    }
    document.body.appendChild(menu);
    _rowMenuEl = menu;
    // Position next to anchor — flip if too close to right edge
    const r = anchor.getBoundingClientRect();
    const mw = 180, mh = items.length * 36 + 8;
    let left = r.right - mw;
    let top = r.bottom + 4;
    if (top + mh > window.innerHeight - 8) top = r.top - mh - 4;
    if (left < 8) left = 8;
    menu.style.left = left + 'px';
    menu.style.top  = top  + 'px';
    // Defer the outside-click close so the same click that opened doesn't immediately shut it
    setTimeout(() => document.addEventListener('mousedown', _outsideMenuClose, { once: false }), 0);
}
function _outsideMenuClose(e) {
    if (_rowMenuEl && !_rowMenuEl.contains(e.target)) closeRowMenu();
}
function closeRowMenu() {
    if (_rowMenuEl) try { _rowMenuEl.remove(); } catch (_) {}
    _rowMenuEl = null;
    document.removeEventListener('mousedown', _outsideMenuClose, { once: false });
}
window.addEventListener('scroll', closeRowMenu, true);
window.addEventListener('hashchange', closeRowMenu);

async function requestView(path, name, size) {
    if (size != null && size > 256 * 1024) {
        if (!confirm(`"${name}" is ${fmtSize(size)}. Preview is capped at 256 KB; the file may be too large.`)) return;
    }
    const appr = await requestForApproval('read', path, size);
    if (appr.kind !== 'ok') return;
    try {
        const url = '/api/files/read?path=' + encodeURIComponent(path)
            + (appr.id ? '&approval=' + encodeURIComponent(appr.id) : '');
        const r = await fetch(url);
        if (!r.ok) {
            const j = await r.json().catch(() => ({}));
            alert('View failed: ' + (j.error || ('HTTP ' + r.status)));
            return;
        }
        const j = await r.json();
        showViewModal(j.name, j.size, j.text || '');
    } catch (e) { alert('View failed: ' + e.message); }
}

function showViewModal(name, size, text) {
    $('viewTitle').textContent = name;
    $('viewMeta').textContent = fmtSize(size);
    $('viewBody').textContent = text;
    $('viewOverlay').hidden = false;
}

async function requestMkdir() {
    const name = prompt(`Create new folder in ${currentPath}:`, 'New folder');
    if (name == null) return;
    const cleaned = name.trim();
    if (!cleaned) return;
    const fullPath = currentPath.replace(/\/$/, '') + '/' + cleaned;
    const appr = await requestForApproval('mkdir', fullPath);
    if (appr.kind !== 'ok') return;
    const url = '/api/files/mkdir?path=' + encodeURIComponent(currentPath)
        + '&name=' + encodeURIComponent(cleaned)
        + (appr.id ? '&approval=' + encodeURIComponent(appr.id) : '');
    try {
        const r = await fetch(url, { method: 'POST' });
        if (!r.ok) {
            const j = await r.json().catch(() => ({}));
            alert('Create folder failed: ' + (j.error || ('HTTP ' + r.status)));
            return;
        }
        loadCurrentPath();
    } catch (e) { alert('Create folder failed: ' + e.message); }
}

async function requestMove(path, name) {
    const dest = prompt(`Move "${name}" to which folder?`, currentPath);
    if (dest == null) return;
    const cleaned = '/' + dest.trim().replace(/^\/+|\/+$/g, '');
    if (!cleaned || cleaned === '/.') return;
    const appr = await requestForApproval('move', path);
    if (appr.kind !== 'ok') return;
    const url = '/api/files/move?path=' + encodeURIComponent(path)
        + '&dest=' + encodeURIComponent(cleaned)
        + (appr.id ? '&approval=' + encodeURIComponent(appr.id) : '');
    try {
        const r = await fetch(url, { method: 'POST' });
        if (!r.ok) {
            const j = await r.json().catch(() => ({}));
            alert('Move failed: ' + (j.error || ('HTTP ' + r.status)));
            return;
        }
        loadCurrentPath();
    } catch (e) { alert('Move failed: ' + e.message); }
}

// ============================================================
//  RENAME / DELETE (with optional approval gate)
// ============================================================
async function requestForApproval(action, path, sizeOpt) {
    if (!me) return { kind: 'ok', id: null };
    const needsByMe =
        (action === 'download' && me.requireDownloadApproval) ||
        (action === 'upload'   && me.requireUploadApproval)   ||
        (action === 'delete'   && me.requireDeleteApproval)   ||
        (action === 'rename'   && me.requireRenameApproval);
    if (!needsByMe) return { kind: 'ok', id: null };
    const fd = new FormData();
    fd.append('action', action);
    fd.append('path', path);
    if (sizeOpt != null) fd.append('size', String(sizeOpt));
    showApprovalToast(`Asking phone owner to approve ${action}…`, path);
    let res;
    try {
        const r = await fetch('/api/approvals/request', { method: 'POST', body: fd });
        res = await r.json();
        if (!r.ok) throw new Error(res.error || ('HTTP ' + r.status));
    } catch (e) {
        hideApprovalToast();
        alert(`Approval request failed: ${e.message}`);
        return { kind: 'fail' };
    }
    if (!res.required) {
        hideApprovalToast();
        return { kind: 'ok', id: null };
    }
    const decision = await pollApproval(res.id);
    hideApprovalToast();
    if (decision === 'approved') return { kind: 'ok', id: res.id };
    if (decision === 'denied') { alert(`${action} denied by phone owner.`); return { kind: 'denied' }; }
    alert('Approval timed out.');
    return { kind: 'expired' };
}

async function requestRename(path, currentName) {
    const newName = prompt(`Rename "${currentName}" to:`, currentName);
    if (newName == null) return;
    const cleaned = newName.trim();
    if (!cleaned || cleaned === currentName) return;
    const appr = await requestForApproval('rename', path);
    if (appr.kind !== 'ok') return;
    const url = '/api/files/rename?path=' + encodeURIComponent(path)
        + '&newName=' + encodeURIComponent(cleaned)
        + (appr.id ? '&approval=' + encodeURIComponent(appr.id) : '');
    try {
        const r = await fetch(url, { method: 'POST' });
        if (!r.ok) {
            const j = await r.json().catch(() => ({}));
            alert('Rename failed: ' + (j.error || ('HTTP ' + r.status)));
            return;
        }
        loadCurrentPath();
    } catch (e) { alert('Rename failed: ' + e.message); }
}

async function requestDelete(path, name, isDir) {
    const what = isDir ? 'folder (and all its contents)' : 'file';
    if (!confirm(`Delete ${what} "${name}"?\n\n${path}\n\nThis cannot be undone.`)) return;
    const appr = await requestForApproval('delete', path);
    if (appr.kind !== 'ok') return;
    const url = '/api/files/delete?path=' + encodeURIComponent(path)
        + (appr.id ? '&approval=' + encodeURIComponent(appr.id) : '');
    try {
        const r = await fetch(url, { method: 'POST' });
        if (!r.ok) {
            const j = await r.json().catch(() => ({}));
            alert('Delete failed: ' + (j.error || ('HTTP ' + r.status)));
            return;
        }
        loadCurrentPath();
    } catch (e) { alert('Delete failed: ' + e.message); }
}

async function pollApproval(id) {
    for (let i = 0; i < 90; i++) { // ~3 min max @ 2s
        await new Promise(r => setTimeout(r, 2000));
        try {
            const r = await fetch('/api/approvals/poll?id=' + encodeURIComponent(id));
            if (!r.ok) continue;
            const j = await r.json();
            if (j.status === 'approved' || j.status === 'denied') return j.status;
            if (j.status === 'expired' || j.status === 'unknown') return 'expired';
        } catch (_) {}
    }
    return 'expired';
}

let approvalCancelHandler = null;
function showApprovalToast(title, sub, onCancel) {
    $('approvalTitle').textContent = title;
    $('approvalSub').textContent = sub || '';
    $('approvalToast').hidden = false;
    approvalCancelHandler = onCancel || null;
    $('approvalCancel').style.display = onCancel ? '' : 'none';
}
function hideApprovalToast() {
    $('approvalToast').hidden = true;
    approvalCancelHandler = null;
}

// ============================================================
//  WEBSOCKET (live refresh)
// ============================================================
let wsKeepaliveTimer = null;

function connectWs() {
    if (!authed) { setWsState('disconnected'); return; }
    // Tear down any prior socket / timer before opening a new one.
    if (wsKeepaliveTimer) { clearInterval(wsKeepaliveTimer); wsKeepaliveTimer = null; }
    if (ws && ws.readyState !== WebSocket.CLOSED && ws.readyState !== WebSocket.CLOSING) {
        try { ws.close(); } catch (_) {}
    }
    try {
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        ws = new WebSocket(proto + '//' + location.host + '/ws');
    } catch (e) { setWsState('disconnected'); scheduleReconnect(); return; }

    setWsState('connecting');

    ws.onopen = () => {
        const wasReconnect = wsRetry > 0;
        wsRetry = 0;
        setWsState('connected');
        // Keepalive: NanoHTTPD applies a 5s SO_TIMEOUT to accepted sockets, so
        // we send something every 4s. Server also pings us every 4s and disables
        // SO_TIMEOUT via reflection — any one of the three keeps us live.
        wsKeepaliveTimer = setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                try { ws.send('{"type":"ping"}'); } catch (_) {}
            }
        }, 4000);
        // Re-sync state after a reconnect: the admin may have changed our role
        // / allowed roots / approval flags while we were disconnected, and the
        // matching `me-changed` / `devices-changed` broadcast was lost.
        if (wasReconnect) {
            loadMe().then(() => {
                if (currentPage === 'files' && !lastSearch.q) loadCurrentPath();
                else if (currentPage === 'notes') loadNotesPage(true);
            });
        }
    };
    ws.onclose = () => {
        if (wsKeepaliveTimer) { clearInterval(wsKeepaliveTimer); wsKeepaliveTimer = null; }
        setWsState('disconnected');
        if (authed) scheduleReconnect();
    };
    ws.onerror = () => { /* close handler will fire too */ };
    ws.onmessage = (ev) => {
        try {
            const msg = JSON.parse(ev.data);
            handleWsMessage(msg);
        } catch (_) {}
    };
}

function handleWsMessage(msg) {
    if (msg.type === 'refresh') {
        if (currentPage === 'files' && !lastSearch.q) {
            if (msg.path === currentPath || normalizePath(msg.path) === normalizePath(currentPath)) {
                loadCurrentPath();
            }
        }
    } else if (msg.type === 'devices-changed' || msg.type === 'me-changed') {
        // The phone may have changed our role / allowed roots. Refresh me, then re-render.
        loadMe().then(() => {
            if (currentPage === 'files' && !lastSearch.q) loadCurrentPath();
        });
    } else if (msg.type === 'notes-changed') {
        // Phone updated our notes — reload if we're not editing.
        if (msg.updatedBy === 'phone') {
            if (currentPage === 'notes') loadNotesPage(true);
            else bumpNotesBadge();
        }
    } else if (msg.type === 'approval-decision') {
        // Already handled by pollApproval(); nothing extra needed.
    }
}

function bumpNotesBadge() {
    const b = $('notesBadge');
    if (!b) return;
    b.hidden = false;
    b.textContent = '!';
}
function clearNotesBadge() {
    const b = $('notesBadge');
    if (!b) return;
    b.hidden = true; b.textContent = '';
}

// ============================================================
//  SHARED NOTES
// ============================================================
let notesLoadedAt = 0;
let notesDirty = false;
let notesAutoSave = null;

async function loadNotesPage(force) {
    clearNotesBadge();
    const editor = $('notesEditor');
    const status = $('notesStatus');
    status.className = 'notes-status';
    status.textContent = 'loading…';
    try {
        const r = await fetch('/api/notes/get');
        if (r.status === 401) {
            authed = false;
            startAuthFlow();
            return;
        }
        if (!r.ok) {
            status.textContent = 'error: HTTP ' + r.status;
            status.classList.add('dirty');
            return;
        }
        const j = await r.json();
        // Only overwrite the textarea if the user isn't actively typing,
        // or we're forcing a refresh from a 'phone' update.
        if (!notesDirty || force) {
            editor.value = j.text || '';
            notesDirty = false;
        }
        notesLoadedAt = j.updatedAt || 0;
        status.classList.add('saved');
        status.textContent = j.updatedAt
            ? `synced ${fmtTime(j.updatedAt)}${j.updatedBy === 'phone' ? ' (from phone)' : ''}`
            : 'empty — start typing';
    } catch (e) {
        status.textContent = 'error: ' + e.message;
        status.classList.add('dirty');
    }
}

async function saveNotes() {
    const editor = $('notesEditor');
    const status = $('notesStatus');
    status.className = 'notes-status';
    status.textContent = 'saving…';
    const fd = new FormData();
    fd.append('text', editor.value);
    try {
        const r = await fetch('/api/notes/set', { method: 'POST', body: fd });
        if (!r.ok) {
            status.textContent = 'save failed: HTTP ' + r.status;
            status.classList.add('dirty');
            return;
        }
        const j = await r.json();
        notesLoadedAt = j.updatedAt;
        notesDirty = false;
        status.classList.add('saved');
        status.textContent = `saved ${fmtTime(j.updatedAt)}`;
    } catch (e) {
        status.textContent = 'save failed: ' + e.message;
        status.classList.add('dirty');
    }
}

function normalizePath(p) {
    return ('/' + (p || '').replace(/^\/+|\/+$/g, '')).replace(/\/+/g, '/') || '/';
}

function scheduleReconnect() {
    wsRetry = Math.min(wsRetry + 1, 6);
    const delay = Math.min(1000 * Math.pow(1.6, wsRetry), 15000);
    setTimeout(connectWs, delay);
}

// ============================================================
//  AUTH FLOW
// ============================================================
function getClientId() {
    let id = localStorage.getItem('phoneshare:client_id');
    if (!id) {
        // Stable fingerprint sent on every challenge so the phone can remember us.
        id = (crypto.randomUUID ? crypto.randomUUID() : 'c-' + Math.random().toString(36).slice(2) + Date.now().toString(36));
        localStorage.setItem('phoneshare:client_id', id);
    }
    return id;
}

async function startAuthFlow() {
    setSplashStatus('Requesting authorization code…');

    let challenge;
    try {
        const r = await fetch('/api/auth/challenge?clientId=' + encodeURIComponent(getClientId()));
        challenge = await r.json();
    } catch (e) {
        setSplashStatus('Network error — retrying…', 'error');
        setTimeout(startAuthFlow, 3000);
        return;
    }

    $('authQr').src = '/api/auth/qr?token=' + encodeURIComponent(challenge.token) + '&t=' + Date.now();
    $('authCode').textContent = challenge.code;
    if (challenge.known) {
        setAuthStatus('Known device "' + (challenge.knownLabel || '') + '" — re-authorize on phone…', 'ok');
    } else {
        setAuthStatus('Waiting for approval on the phone…', '');
    }

    const splash = $('splash');
    if (splash && !splash.hidden) {
        splash.classList.add('fade-out');
        setTimeout(() => { splash.hidden = true; splash.classList.remove('fade-out'); }, 240);
    }
    $('authOverlay').hidden = false;

    if (authPollAbort) authPollAbort.aborted = true;
    const myAbort = { aborted: false };
    authPollAbort = myAbort;

    while (!myAbort.aborted) {
        await new Promise(r => setTimeout(r, 2000));
        if (myAbort.aborted) return;
        let status;
        try {
            const r = await fetch('/api/auth/status?token=' + encodeURIComponent(challenge.token));
            status = await r.json();
        } catch (e) {
            continue;
        }
        if (status.status === 'approved') {
            setAuthStatus('Approved — loading…', 'ok');
            authed = true;
            $('authOverlay').hidden = true;
            const splash = $('splash');
            if (splash) {
                splash.hidden = false;
                splash.classList.remove('fade-out');
                setSplashStatus('Loading files…');
            }
            applyRoute();
            connectWs();
            return;
        }
        if (status.status === 'expired') {
            setAuthStatus('Code expired — generating new one…', 'error');
            startAuthFlow();
            return;
        }
    }
}

function setAuthStatus(text, kind) {
    const el = $('authStatus');
    if (!el) return;
    el.textContent = text;
    el.classList.remove('error', 'ok');
    if (kind) el.classList.add(kind);
}

function setWsState(state) {
    const el = $('wsIndicator');
    el.classList.remove('connected', 'disconnected', 'connecting');
    el.classList.add(state);
    $('wsLabel').textContent = state === 'connected' ? 'live'
        : state === 'connecting' ? 'connecting…' : 'offline';
}

// ============================================================
//  UPLOAD (with optional approval gate)
// ============================================================
async function startUpload(file) {
    const id = 'u' + Math.random().toString(36).slice(2);
    const card = renderUploadCard(id, file.name);

    // If guest mode requires upload approval, ask first.
    let approvalId = null;
    if (me && me.requireUploadApproval) {
        card.el.classList.add('waiting');
        card.pct.textContent = 'wait…';
        card.stats.textContent = 'awaiting approval';
        const fd = new FormData();
        fd.append('action', 'upload');
        fd.append('path', currentPath + '/' + file.name);
        fd.append('size', String(file.size));
        let res;
        try {
            const r = await fetch('/api/approvals/request', { method: 'POST', body: fd });
            res = await r.json();
            if (!r.ok) throw new Error(res.error || ('HTTP ' + r.status));
        } catch (e) {
            failCard(card, 'approval request failed: ' + e.message);
            return;
        }
        if (res.required) {
            const decision = await pollApproval(res.id);
            if (decision !== 'approved') {
                failCard(card, decision === 'denied' ? 'denied by phone owner' : 'approval timed out');
                return;
            }
            approvalId = res.id;
        }
        card.el.classList.remove('waiting');
        card.pct.textContent = '0%';
        card.stats.textContent = 'starting…';
    }

    const xhr = new XMLHttpRequest();
    activeUploads.set(id, { xhr, name: file.name });

    const fd = new FormData();
    fd.append('file', file, file.name);

    const startedAt = performance.now();
    let lastUpdate = 0;

    xhr.upload.onprogress = (e) => {
        if (!e.lengthComputable) return;
        const now = performance.now();
        if (now - lastUpdate < 100 && e.loaded < e.total) return;
        lastUpdate = now;
        const elapsed = (now - startedAt) / 1000;
        const speed = elapsed > 0 ? e.loaded / elapsed : 0;
        const eta = speed > 0 ? (e.total - e.loaded) / speed : Infinity;
        const pct = Math.round((e.loaded / e.total) * 100);
        card.fill.style.width = pct + '%';
        card.pct.textContent = pct + '%';
        card.stats.textContent = `${fmtSize(e.loaded)} / ${fmtSize(e.total)} · ${fmtSpeed(speed)} · ETA ${fmtETA(eta)}`;
    };

    xhr.onload = () => {
        activeUploads.delete(id);
        let body = {};
        try { body = JSON.parse(xhr.responseText) || {}; } catch (_) {}
        if (xhr.status === 401) { authed = false; failCard(card, 'unauthorized'); startAuthFlow(); return; }
        if (xhr.status < 200 || xhr.status >= 300) {
            failCard(card, body.error || ('HTTP ' + xhr.status));
            return;
        }
        const failedEntry = pickFailedFor(body, file.name);
        const wasSaved = (body.saved && body.saved.length > 0);
        if (failedEntry && !wasSaved) {
            failCard(card, prettifyError(failedEntry.error));
            return;
        }
        card.el.classList.add('done');
        card.pct.textContent = '✓ done';
        card.stats.textContent = fmtSize(file.size);
        card.cancel.style.display = 'none';
        if (currentPage === 'files' && !lastSearch.q) loadCurrentPath();
        setTimeout(() => fadeRemove(card.el), 2500);
    };
    xhr.onerror = () => { activeUploads.delete(id); failCard(card, 'network error'); };
    xhr.onabort = () => { activeUploads.delete(id); failCard(card, 'cancelled'); };

    card.cancel.onclick = () => xhr.abort();

    let url = '/api/upload?dest=' + encodeURIComponent(currentPath);
    if (approvalId) url += '&approval=' + encodeURIComponent(approvalId);
    xhr.open('POST', url);
    xhr.send(fd);
}

function renderUploadCard(id, name) {
    const tpl = $('uploadCardTemplate').content.cloneNode(true);
    const el = tpl.querySelector('.upload-card');
    el.dataset.id = id;
    const refs = {
        el,
        name: el.querySelector('.uc-name'),
        pct: el.querySelector('.uc-pct'),
        fill: el.querySelector('.uc-fill'),
        stats: el.querySelector('.uc-stats'),
        cancel: el.querySelector('.uc-cancel'),
    };
    refs.name.textContent = name;
    $('uploadStack').appendChild(el);
    return refs;
}

function failCard(card, msg) {
    card.el.classList.remove('waiting');
    card.el.classList.add('error');
    card.pct.textContent = '✗';
    card.stats.textContent = msg;
    card.cancel.textContent = 'dismiss';
    card.cancel.onclick = () => fadeRemove(card.el);
}

function pickFailedFor(body, name) {
    const list = body && body.failed;
    if (!Array.isArray(list) || list.length === 0) return null;
    return list.find(f => f && f.name === name) || list[0];
}

function prettifyError(msg) {
    if (!msg) return 'failed';
    let s = String(msg);
    const colon = s.indexOf(': ');
    if (colon > 0 && s.startsWith('/')) s = s.slice(colon + 2);
    if (s.length > 160) s = s.slice(0, 157) + '…';
    return s;
}

function fadeRemove(el) {
    el.style.transition = 'opacity 0.3s, transform 0.3s';
    el.style.opacity = '0';
    el.style.transform = 'translateY(8px)';
    setTimeout(() => el.remove(), 320);
}

// (Devices and Logs pages were removed — managed on the phone now.)

// ============================================================
//  WIRING
// ============================================================
function init() {
    // Sidebar toggle (mobile)
    $('navToggle').onclick = () => $('sidebar').classList.toggle('open');

    // Files page
    $('refreshBtn').onclick = () => { lastSearch.q ? doSearch(lastSearch.q, lastSearch.deep) : loadCurrentPath(); };
    $('uploadBtn').onclick = () => $('filePicker').click();
    $('mkdirBtn').onclick  = requestMkdir;
    $('viewClose').onclick = () => $('viewOverlay').hidden = true;
    $('viewOverlay').addEventListener('click', (e) => {
        if (e.target.id === 'viewOverlay') $('viewOverlay').hidden = true;
    });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !$('viewOverlay').hidden) $('viewOverlay').hidden = true;
    });
    $('filePicker').onchange = (e) => {
        for (const f of e.target.files) startUpload(f);
        e.target.value = '';
    };
    $('searchInput').addEventListener('input', onSearchInput);
    $('searchDeep').addEventListener('change', () => {
        if (lastSearch.q) doSearch($('searchInput').value.trim(), $('searchDeep').checked);
    });
    $('sortSelect').addEventListener('change', () => {
        localStorage.setItem('phoneshare:sort', $('sortSelect').value);
        if (lastSearch.q) doSearch(lastSearch.q, lastSearch.deep);
        else loadCurrentPath();
    });
    const savedSort = localStorage.getItem('phoneshare:sort');
    if (savedSort) $('sortSelect').value = savedSort;

    // Whole-list (file table) drag-and-drop
    const wrap = $('fileTableWrap');
    ['dragenter','dragover'].forEach(ev => wrap.addEventListener(ev, (e) => {
        e.preventDefault(); e.stopPropagation();
        wrap.classList.add('drag-over');
    }));
    ['dragleave'].forEach(ev => wrap.addEventListener(ev, (e) => {
        if (!wrap.contains(e.relatedTarget)) wrap.classList.remove('drag-over');
    }));
    wrap.addEventListener('drop', (e) => {
        e.preventDefault(); e.stopPropagation();
        wrap.classList.remove('drag-over');
        if (e.dataTransfer && e.dataTransfer.files) {
            for (const f of e.dataTransfer.files) startUpload(f);
        }
    });

    // Whole-window drop overlay (only when dragging files into the window)
    let dragDepth = 0;
    window.addEventListener('dragenter', (e) => {
        if (e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files')) {
            dragDepth++;
            $('dropOverlay').hidden = false;
        }
    });
    window.addEventListener('dragleave', () => {
        dragDepth = Math.max(0, dragDepth - 1);
        if (dragDepth === 0) $('dropOverlay').hidden = true;
    });
    window.addEventListener('dragover', (e) => {
        if (e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files')) {
            e.preventDefault();
        }
    });
    window.addEventListener('drop', (e) => {
        dragDepth = 0;
        $('dropOverlay').hidden = true;
        if (!e.target.closest('.file-table-wrap') && e.dataTransfer && e.dataTransfer.files) {
            e.preventDefault();
            for (const f of e.dataTransfer.files) startUpload(f);
        }
    });

    // Notes page
    $('notesSaveBtn').onclick = saveNotes;
    $('notesEditor').addEventListener('input', () => {
        notesDirty = true;
        const status = $('notesStatus');
        status.className = 'notes-status dirty';
        status.textContent = 'unsaved…';
        if (notesAutoSave) clearTimeout(notesAutoSave);
        notesAutoSave = setTimeout(() => { if (notesDirty) saveNotes(); }, 1500);
    });

    // Settings
    const savedDensity = localStorage.getItem('phoneshare:density') || 'comfortable';
    $('uiDensity').value = savedDensity;
    document.body.classList.toggle('compact', savedDensity === 'compact');
    $('uiDensity').onchange = () => {
        const v = $('uiDensity').value;
        localStorage.setItem('phoneshare:density', v);
        document.body.classList.toggle('compact', v === 'compact');
    };
    const arEl = $('autoRefreshLogs');
    if (arEl) {
        // Setting kept for future, but no log page anymore — hide the row.
        const row = arEl.closest('.setting-row');
        if (row) row.style.display = 'none';
    }
    $('signOutBtn').onclick = async () => {
        if (!confirm('Sign out this device?')) return;
        // Server response sets `Set-Cookie: ...; Max-Age=0` which clears the
        // HttpOnly cookie; the JS cannot wipe it directly (HttpOnly).
        try { await fetch('/api/auth/signout'); } catch (_) {}
        authed = false;
        if (ws) try { ws.close(); } catch (_) {}
        location.reload();
    };

    // Approval toast cancel
    $('approvalCancel').onclick = () => {
        if (approvalCancelHandler) approvalCancelHandler();
        hideApprovalToast();
    };

    // Initial route + auth probe (works for any starting page, not just files)
    loadDeviceName();
    ensureAuthThenApply();
}

document.addEventListener('DOMContentLoaded', init);
