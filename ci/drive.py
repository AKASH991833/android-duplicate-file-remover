import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

API = int(sys.argv[1])
OUT = os.environ.get('OUT_DIR') or os.path.join(os.environ.get('GITHUB_WORKSPACE', '/tmp'), 'out')
os.makedirs(OUT, exist_ok=True)
PKG = 'com.akash.duperemover'
results = []

def run(cmd):
    return subprocess.run(cmd, capture_output=True)

def check(name, cond, extra=''):
    results.append((name, bool(cond)))
    print(('PASS' if cond else 'FAIL') + ': ' + name + (' | ' + str(extra) if extra else ''), flush=True)

def finish(code):
    with open(os.path.join(OUT, 'results.txt'), 'w') as f:
        for name, ok in results:
            f.write(('PASS' if ok else 'FAIL') + ': ' + name + '\n')
    r = run(['adb', 'logcat', '-d'])
    with open(os.path.join(OUT, 'logcat.txt'), 'w') as f:
        f.write(r.stdout.decode('utf-8', 'ignore'))
    failed = [n for n, ok in results if not ok]
    print('==== %d/%d checks passed ====' % (len(results) - len(failed), len(results)), flush=True)
    sys.exit(code)

def die(msg):
    print('ABORT: ' + msg, flush=True)
    xml = dump()
    save_debug(xml, 'die')
    r = run(['adb', 'shell', 'dumpsys', 'window', 'windows'])
    with open(os.path.join(OUT, 'windows.txt'), 'w') as f:
        f.write(r.stdout.decode('utf-8', 'ignore'))
    finish(1)

def shot(name):
    p = os.path.join(OUT, name + '.png')
    r = run(['adb', 'exec-out', 'screencap', '-p'])
    if r.stdout.startswith(b'\x89PNG'):
        with open(p, 'wb') as f:
            f.write(r.stdout)
        return p
    run(['adb', 'shell', 'screencap', '-p', '/sdcard/' + name + '.png'])
    run(['adb', 'pull', '/sdcard/' + name + '.png', p])
    return p

def dump():
    xml = ''
    for _ in range(4):
        run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'])
        time.sleep(0.5)
        r = run(['adb', 'shell', 'cat', '/sdcard/ui.xml'])
        xml = r.stdout.decode('utf-8', 'ignore')
        if '<hierarchy' in xml:
            break
        time.sleep(1)
    return xml

def find_all(xml, text=None, contains=None, desc=None, desc_contains=None, clazz=None,
             clickable=None, regex=None):
    out = []
    try:
        root = ET.fromstring(xml)
    except Exception:
        return out
    if root is None:
        return out
    for n in root.iter('node'):
        t = n.get('text') or ''
        d = n.get('content-desc') or ''
        if text is not None and t != text:
            continue
        if contains is not None and contains not in t:
            continue
        if desc is not None and d != desc:
            continue
        if desc_contains is not None and desc_contains not in d:
            continue
        if regex is not None and not re.search(regex, t):
            continue
        if clazz is not None and not (n.get('class') or '').endswith(clazz):
            continue
        if clickable is not None and (n.get('clickable') == 'true') != clickable:
            continue
        out.append(n)
    return out

def center(n):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds'))
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2

def tap_node(n):
    x, y = center(n)
    run(['adb', 'shell', 'input', 'tap', str(x), str(y)])

def screen_size():
    r = run(['adb', 'shell', 'wm', 'size'])
    m = re.search(r'(\d+)x(\d+)', r.stdout.decode('utf-8', 'ignore'))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)

def swipe_up():
    w, h = screen_size()
    run(['adb', 'shell', 'input', 'swipe', str(w // 2), str(int(h * 0.7)), str(w // 2), str(int(h * 0.3)), '400'])

def wait_node(timeout=30, **kw):
    end = time.time() + timeout
    xml = dump()
    while time.time() < end:
        anr = find_all(xml, regex=r"isn't responding")
        if anr:
            w = find_all(xml, text='Wait')
            if w:
                print('ANR dialog seen - tapping Wait', flush=True)
                tap_node(w[0])
                time.sleep(3)
                xml = dump()
                end += 30
                continue
        r = find_all(xml, **kw)
        if r:
            return xml, r[0]
        time.sleep(2)
        xml = dump()
    return xml, None

def save_debug(xml, name):
    with open(os.path.join(OUT, name + '.xml'), 'w') as f:
        f.write(xml)
    shot(name)

def tap_text(timeout=20, **kw):
    xml, n = wait_node(timeout=timeout, **kw)
    if n is None:
        save_debug(xml, 'stuck')
        return None
    tap_node(n)
    return n

def scroll_until(swipes=10, **kw):
    for _ in range(swipes):
        xml = dump()
        anr = find_all(xml, regex=r"isn't responding")
        if anr:
            w = find_all(xml, text='Wait')
            if w:
                tap_node(w[0])
                time.sleep(3)
                continue
        r = find_all(xml, **kw)
        if r:
            return xml, r[0]
        swipe_up()
        time.sleep(1.5)
    return dump(), None

def summary_text(xml):
    r = find_all(xml, regex=r'\d+ exact groups \|')
    return r[0].get('text') if r else ''

def wait_scan_done(timeout=300):
    xml, n = wait_node(timeout=timeout, contains='Scan complete')
    return xml, n

def path_exists(p):
    r = run(['adb', 'shell', 'test', '-e', p, '&&', 'echo', 'YES'])
    return 'YES' in r.stdout.decode('utf-8', 'ignore')

def confirm_and_delete(expect_button, expect_status, shot_name):
    xml, n = wait_node(timeout=20, text=expect_button)
    check('Select all exact -> %s' % expect_button, n is not None)
    if n is None:
        die('select-all button never showed ' + expect_button)
    tap_node(n)
    xml, n = wait_node(timeout=20, contains='PERMANENTLY deleted')
    check('Permanent-delete confirmation dialog shown', n is not None)
    shot(shot_name)
    if n is None:
        die('no confirm dialog')
    tap_text(regex=r'(?i)^delete permanently$')
    xml, n = wait_node(timeout=300, contains='files deleted')
    status_nodes = find_all(xml, contains='files deleted')
    st = status_nodes[0].get('text') if status_nodes else ''
    check('Deletion reports %s' % expect_status, expect_status in st, st)
    if n is None:
        die('delete did not finish')
    return xml

run(['adb', 'logcat', '-c'])
run(['adb', 'shell', 'wm', 'dismiss-keyguard'])
run(['adb', 'shell', 'input', 'keyevent', '82'])

apk = os.environ.get('APK_PATH', 'app/build/outputs/apk/debug/app-debug.apk')
r = run(['adb', 'install', '-r', apk])
check('APK installs', b'Success' in r.stdout, r.stdout.decode('utf-8', 'ignore')[-200:])

r = run(['adb', 'shell', 'am', 'start', '-n', PKG + '/.MainActivity'])
print('am start: ' + r.stdout.decode('utf-8', 'ignore').strip()[:300], flush=True)
xml, n = wait_node(timeout=120, text='Duplicate File Remover')
check('App launches, home screen shown', n is not None)
shot('01_home')
if n is None:
    die('app did not launch or title missing')

# --- S2: permission UX without granting yet ---
if API >= 30:
    tap_text(text='Full phone')
    xml, n = wait_node(timeout=20, contains='All files access')
    check('All-files-access explanation dialog shown before any grant', n is not None)
    shot('02_access_dialog')
    tap_text(text='Cancel')
    time.sleep(1)
else:
    tap_text(text='Full phone')
    xml, n = wait_node(timeout=20, contains='Allow Duplicate File Remover')
    check('Runtime permission dialog shown', n is not None)
    shot('02_permission_dialog')
    tap_text(regex=r'(?i)^deny$')
    xml, n = wait_node(timeout=20, contains='denied')
    check('Deny path shows guidance status', n is not None)

# --- S3: folder (SAF) mode on DupeTest2 first, while its duplicate pair is intact ---
MEDIA_ROOTS = ('recent', 'images', 'videos', 'audio', 'documents', 'downloads')
CTRL_RE = re.compile(r'(?i)(use this folder|cancel|show roots|list view|grid view|more options|search|sort|no items|files on|select all|new folder)')

def nav_debug(tag, xml):
    texts, descs = [], []
    try:
        for x in ET.fromstring(xml).iter('node'):
            if (x.get('text') or ''): texts.append(x.get('text'))
            if (x.get('content-desc') or ''): descs.append(x.get('content-desc'))
    except Exception:
        pass
    with open(os.path.join(OUT, 'nav_%s.txt' % tag), 'w') as f:
        f.write('texts: %r\ndescs: %r\n' % (sorted(set(texts)), sorted(set(descs))))

def tap_storage_root(xml):
    # 1) free-space summary row ("4.2 GB free") is unique to the device storage root
    r = find_all(xml, regex=r'(?i)\d+(\.\d+)?\s?[KMG]B\s+(available|free)')
    if r:
        tap_node(r[0]); time.sleep(2); return True
    # 2) device-model / internal-storage label heuristics
    for pat in (r'(?i)(sdk_|gphone|pixel|emulator|emu\d|internal storage|phone storage|shared storage)',):
        r = [n for n in find_all(xml, regex=pat)
             if (n.get('text') or '').strip().lower() not in MEDIA_ROOTS]
        if r:
            tap_node(r[0]); time.sleep(2); return True
    # 3) any drawer row title that is not a known media root or a control
    for n in find_all(xml):
        t = (n.get('text') or '').strip()
        if t and t.lower() not in MEDIA_ROOTS and not CTRL_RE.search(t) \
                and not re.search(r'(?i)\b(KB|MB|GB)\b', t) and len(t) < 40:
            tap_node(n); time.sleep(2); return True
    return False

tap_text(text='Choose folder')
xml, n = wait_node(timeout=30, regex=r'(?i)(use this folder|allow access|open from|show roots|files on|no items)')
check('DocumentsUI picker opened', n is not None)
shot('03_picker_open')
if n is None:
    nav_debug('picker_missing', dump())
    die('DocumentsUI picker never opened')

# On the stripped CI emulator images the roots drawer exposes Downloads only;
# the picker opens on it with an enabled "ALLOW ACCESS TO ..." button. If we
# somehow land elsewhere, open the drawer and tap the Downloads root.
xml, n = wait_node(timeout=8, regex=r'(?i)allow access to')
if n is None:
    xml, t = wait_node(timeout=5, desc='Show roots')
    if t is None:
        xml, t = wait_node(timeout=5, text='Open from')
    if t is not None:
        tap_node(t)
        time.sleep(2)
    xml = dump()
    nav_debug('drawer', xml)
    xml, d = wait_node(timeout=10, text='Downloads')
    if d is not None:
        tap_node(d)
        time.sleep(2)
    xml, n = wait_node(timeout=10, regex=r'(?i)allow access to')
check('Picker shows Allow-access-to-Downloads button', n is not None)
shot('04_picker_folder')
if n is None:
    nav_debug('allowbtn', dump())
    die('could not reach Allow access button in picker')
tap_node(n)
xml, n = wait_node(timeout=15, regex=r'(?i)^allow$')
if n is not None:
    tap_node(n)
xml, n = wait_scan_done()
st = summary_text(xml)
check('Folder-mode scan completes', n is not None)
if n is None:
    die('folder-mode scan did not complete')
check('Folder-mode finds 1 exact group', '1 exact groups | 0 similar' in st, st)
shot('05_folder_results')
tap_text(text='Select all exact')
confirm_and_delete('Delete (1)', '1 files deleted; 0 skipped', '06_folder_delete')
shot('07_folder_deleted')
check('SAF delete removed saf2.txt', not path_exists('/sdcard/Download/saf2.txt'))
check('SAF keep saf1.txt survives', path_exists('/sdcard/Download/saf1.txt'))

# --- S4: switch to full-phone mode ---
if API >= 30:
    run(['adb', 'shell', 'appops', 'set', PKG, 'MANAGE_EXTERNAL_STORAGE', 'allow'])
    r = run(['adb', 'shell', 'appops', 'get', PKG, 'MANAGE_EXTERNAL_STORAGE'])
    check('MANAGE_EXTERNAL_STORAGE granted via appops', 'allow' in r.stdout.decode('utf-8', 'ignore').lower())
    tap_text(text='Full phone')
else:
    tap_text(text='Full phone')
    xml, n = wait_node(timeout=20, contains='Allow Duplicate File Remover')
    if n is not None:
        tap_text(regex=r'(?i)^allow$')

xml, n = wait_scan_done()
st = summary_text(xml)
check('Full-phone scan completes', n is not None)
if n is None:
    die('full-phone scan did not complete')
check('All-filter summary shows 6 exact / 1 similar group', '6 exact groups | 1 similar' in st, st)
rep = find_all(xml, contains='By folder')
rep_text = rep[0].get('text') if rep else ''
check('Report lists Photos: 4 extra copies', 'Photos: 4' in rep_text, rep_text[:200])
check('Report lists Documents: 2 extra copies', 'Documents: 2' in rep_text, rep_text[:200])
check('Report lists Videos: 1 extra copy', 'Videos: 1' in rep_text, rep_text[:200])
check('Report counts DupeTest folder copies', 'DupeTest: 6' in rep_text, rep_text[:300])
check('Report counts DupeTest/sub copy', 'DupeTest/sub: 1' in rep_text, rep_text[:300])
shot('08_results')

# --- S5: thumbnail preview (scroll until a photo card is visible) ---
xml, img = scroll_until(clazz='ImageView', clickable=True)
if img is not None:
    tap_node(img)
    xml, n = wait_node(timeout=20, regex=r'(?i)^close$')
    check('Thumbnail opens preview dialog', n is not None)
    shot('09_preview')
    tap_text(regex=r'(?i)^close$')
    run(['adb', 'shell', 'input', 'keyevent', '4'])
    xml = dump()
    anr = find_all(xml, regex=r"isn't responding")
    if anr:
        w = find_all(xml, text='Wait')
        if w:
            tap_node(w[0])
else:
    check('Thumbnail opens preview dialog', False, 'no clickable ImageView found after scrolling')

# --- S6: type filters ---
for name, exp in [('Photos', '3 exact groups | 1 similar'),
                  ('Videos', '1 exact groups | 0 similar'),
                  ('Documents', '2 exact groups | 0 similar'),
                  ('All files', '6 exact groups | 1 similar')]:
    tap_text(text=name)
    time.sleep(1)
    tap_text(text='Scan again')
    xml, n = wait_scan_done()
    st = summary_text(xml)
    check('Filter %s scan completes' % name, n is not None)
    check('Filter %s summary = %s' % (name, exp), exp in st, st)
    shot('10_filter_' + name.lower().replace(' ', '_'))

# --- S7: select all exact + permanent delete ---
tap_text(text='Select all exact')
confirm_and_delete('Delete (7)', '7 files deleted; 0 skipped', '11_confirm_delete')
shot('12_after_delete')

for p in ['/sdcard/DupeTest/photoA_copy.jpg', '/sdcard/DupeTest/sub/photoA_third.jpg',
          '/sdcard/DupeTest/big2.jpg', '/sdcard/DupeTest/empty2.jpg',
          '/sdcard/DupeTest/doc1_copy.txt', '/sdcard/DupeTest/fake2.pdf',
          '/sdcard/DupeTest/vid2.mp4']:
    check('Deleted from disk: ' + p, not path_exists(p))
for p in ['/sdcard/DupeTest/photoA.jpg', '/sdcard/DupeTest/big1.jpg',
          '/sdcard/DupeTest/empty1.jpg', '/sdcard/DupeTest/doc1.txt',
          '/sdcard/DupeTest/fake1.pdf', '/sdcard/DupeTest/vid1.mp4',
          '/sdcard/DupeTest/sim_base.jpg', '/sdcard/DupeTest/sim_tweak.jpg',
          '/sdcard/DupeTest/unique_photo.jpg', '/sdcard/DupeTest/doc2.txt']:
    check('KEEP survives: ' + p, path_exists(p))

r = run(['adb', 'logcat', '-d'])
log = r.stdout.decode('utf-8', 'ignore')
fatals = [l for l in log.splitlines() if 'FATAL EXCEPTION' in l]
check('No FATAL EXCEPTION in logcat', not fatals, fatals[:3])
finish(1 if any(not ok for _, ok in results) else 0)
