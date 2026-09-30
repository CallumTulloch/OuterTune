"""UI companion to WorkflowReplayDeviceTest; see the 2026-09-30 emulator verification record."""
import time, re, json, sys, xml.etree.ElementTree as ET
import argparse, subprocess, pathlib

parser = argparse.ArgumentParser(description="Drive WorkflowReplayDeviceTest on a disposable emulator (English UI).")
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True)
parser.add_argument('--port', default='5037')
parser.add_argument('--cycles', type=int, choices=range(7, 13), default=7)
parser.add_argument('--output', type=pathlib.Path, required=True)
parser.add_argument('--finish', action='store_true', help='After observing idle, signal instrumentation to validate and finish')
parser.add_argument('--idle-seconds', type=int, default=200)
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('Only a disposable emulator is supported')
def adb(*command):
    return subprocess.check_output([args.adb, '-P', args.port, '-s', args.serial, *command], timeout=45)
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
events = (out / 'events.jsonl').open('a')
def event(phase, **extra):
    record = {'time':time.time(), 'phase':phase, **extra}
    events.write(json.dumps(record) + '\n'); events.flush()
    print(json.dumps(record), flush=True)
def screen():
    last_error = None
    for attempt in range(8):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/workflow-auto.xml')
            raw = adb('exec-out', 'cat', '/sdcard/workflow-auto.xml')
            return ET.fromstring(raw), raw
        except (ET.ParseError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            # Retry a read after temporary emulator/ADB disconnects; never repeat a tap blindly.
            last_error = exc
            time.sleep(1)
    raise AssertionError('UI hierarchy unavailable') from last_error
def find(root, value, above=None):
    return next((n for n in root.iter('node') if
        (n.get('text') == value or n.get('content-desc') == value) and
        (above is None or int(re.findall(r'\d+', n.get('bounds'))[1]) < above)), None)
def wait(value, timeout=60, above=None):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        root, raw=screen()
        node=find(root,value,above)
        if node is not None: return node,raw
        time.sleep(.5)
    (out / 'failed-screen.xml').write_bytes(raw)
    (out / 'failed-screen.png').write_bytes(adb('exec-out','screencap','-p'))
    raise AssertionError('UI did not show '+value)
def tap_node(node):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def click(value,timeout=60,above=None):
    node,_=wait(value,timeout,above); tap_node(node); time.sleep(.7)
def shot(name,raw=None):
    if raw is None: _,raw=screen()
    (out / (name+'.xml')).write_bytes(raw)
    (out / (name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
try:
    # Runtime permission prompts are unrelated to the replay and obscure the initial Home screen.
    # This companion is restricted above to the disposable API35 emulator installation.
    for permission in ('android.permission.READ_MEDIA_AUDIO', 'android.permission.POST_NOTIFICATIONS'):
        adb('shell', 'pm', 'grant', 'com.dd3boh.outertune.debug', permission)
    size = adb('shell', 'wm', 'size').decode()
    if '1080x2424' not in size:
        raise AssertionError('Replay requires Pixel 9 at 1080x2424; adapt the row-menu coordinate for another layout')
    for album in range(1, args.cycles+1):
        event('start-cycle',album=album)
        click('Home')
        click('Search')
        # A mounted search entry can retain its last query. Clear from the observed field.
        for attempt in range(15):
            root,_=screen()
            field=next((n for n in root.iter('node') if n.get('class')=='android.widget.EditText'),None)
            if field is not None: break
            time.sleep(.5)
        if field is None: raise AssertionError('Search input did not mount')
        tap_node(field)
        adb('shell','input','keyevent','123')
        for _ in field.get('text',''): adb('shell','input','keyevent','67')
        adb('shell','input','text',f'workflow{album}')
        wait(f'workflow{album}')
        adb('shell','input','keyevent','66')
        # Host connectivity can briefly disappear although fixture HTTP remains local. Search
        # intentionally stays local after reconnection; explicitly choose this replay's source.
        click('Online')
        # A hardware Enter can be consumed while the search field is gaining focus. If the
        # observed UI is still the suggestions page, submit its exact query row once instead.
        root,_=screen()
        if find(root,'Songs',above=1200) is None:
            suggestion=next((n for n in root.iter('node') if n.get('text')==f'workflow{album}'
                and n.get('class')!='android.widget.EditText'
                and 300 < int(re.findall(r'\d+',n.get('bounds'))[1]) < 1900),None)
            if suggestion is not None: tap_node(suggestion)
        # The bottom navigation also says Songs; only the search filter is a valid target.
        click('Songs',above=1200)
        node,raw=wait(f'The morning light {album} track 1')
        shot(f'{album:02}-search',raw)
        _,y1,_,y2=map(int,re.findall(r'\d+',node.get('bounds')))
        adb('shell','input','tap','1015',str((y1+y2)//2))
        click('View album')
        _,raw=wait('12 songs • 2026')
        shot(f'{album:02}-album',raw)
        event('album-loaded',album=album)
        node,_=wait(f'The morning light {album} track 1')
        _,y1,_,y2=map(int,re.findall(r'\d+',node.get('bounds')))
        adb('shell','input','tap','1015',str((y1+y2)//2))
        click('Add to library')
        wait('Remove from library')
        event('saved',album=album)
        click('Download')
        node,raw=wait('Remove download',90)
        shot(f'{album:02}-downloaded',raw)
        event('downloaded',album=album)
        click('Play')
        time.sleep(3)
        shot(f'{album:02}-playing')
        state = adb('shell', 'dumpsys', 'media_session').decode(errors='replace')
        (out / f'{album:02}-media-session.txt').write_text(state, encoding='utf-8')
        own_session = state.split('package=com.dd3boh.outertune.debug', 1)[1].split('metadata:', 1)
        assert 'state=PLAYING(3)' in own_session[0], 'Player must be playing the downloaded audio'
        assert f'The morning light {album} track 1' in own_session[1], 'Player must select this song'
        event('playing',album=album)
        # Return to the underlying page if Play expanded the player.
        root,_=screen()
        if find(root,'Home') is None: adb('shell','input','keyevent','4')
    event('cycles-complete')
    if args.finish:
        time.sleep(max(0, args.idle_seconds))
        (out / 'final-media-session.txt').write_bytes(adb('shell', 'dumpsys', 'media_session'))
        # The instrumentation already samples the Java heap and GC. meminfo may request a GC
        # and would perturb the final idle interval used for this performance comparison.
        adb('shell', 'run-as', 'com.dd3boh.outertune.debug', 'touch', 'files/workflow-stop')
        event('instrumentation-stop-requested')
except Exception as exc:
    event('failed',error=str(exc))
    raise
finally:
    events.close()
