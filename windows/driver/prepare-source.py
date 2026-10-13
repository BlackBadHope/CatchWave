# SPDX-License-Identifier: GPL-3.0-or-later
"""Create a separate, pinned SysVAD-derived source tree; never installs a driver."""
import argparse, hashlib, json, re, shutil, subprocess
from pathlib import Path

COMMIT = '2dc3fd3a0cc84a2933f2194e7ec0871584979071'
p = argparse.ArgumentParser()
p.add_argument('--upstream', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
actual = subprocess.check_output(['git', '-C', str(a.upstream), 'rev-parse', 'HEAD'], text=True).strip()
if actual != COMMIT:
    raise SystemExit('Wrong upstream revision; no output created')
if subprocess.check_output(['git', '-C', str(a.upstream), 'status', '--porcelain', '--', 'audio/sysvad'], text=True).strip():
    raise SystemExit('Modified upstream; no output created')
if a.output.exists():
    raise SystemExit('Output already exists; preserve it and choose a fresh directory')
shutil.copytree(a.upstream / 'audio/sysvad', a.output)
shutil.copyfile(a.upstream / 'LICENSE', a.output / 'LICENSE-Microsoft')

def change(path, old, new):
    text = path.read_text(encoding='utf-8-sig')
    if old not in text:
        raise SystemExit(f'Pinned source mismatch: {path.name}')
    path.write_text(text.replace(old, new), encoding='utf-8')

mini = a.output / 'TabletAudioSample/minipairs.h'
change(mini, '    &SpeakerHpMiniports,\n    &HdmiMiniports,\n    &SpdifMiniports,\n', '')
change(mini, '#define g_cCaptureEndpoints (SIZEOF_ARRAY(g_CaptureEndpoints))', '#define g_cCaptureEndpoints 0 // CatchWave has no microphone endpoint.')
# Disable raw render recording in code, including the registry override. No audio file logging.
adapter = a.output / 'adapter.cpp'
text = adapter.read_text(encoding='utf-8-sig')
lines = text.splitlines(keepends=True)
matching = [line for line in lines if 'L"DoNotCreateDataFiles"' in line]
if len(matching) != 1:
    raise SystemExit('Pinned registry query mismatch')
adapter.write_text(''.join(line for line in lines if line not in matching), encoding='utf-8')
change(adapter, 'DWORD g_DoNotCreateDataFiles = 1;', 'DWORD g_DoNotCreateDataFiles = 1; // Always disabled in CatchWave.')
adapter_text = adapter.read_text(encoding='utf-8')
capture_body = re.compile(r'(InstallAllCaptureFilters\([^)]*\)\n)\{\n.*?^\}', re.M | re.S)
adapter_text, capture_removed = capture_body.subn(lambda m: m.group(1) + '{\n    PAGED_CODE();\n    UNREFERENCED_PARAMETER(_pDeviceObject);\n    UNREFERENCED_PARAMETER(_pIrp);\n    UNREFERENCED_PARAMETER(_pAdapterCommon);\n    return STATUS_SUCCESS; // CatchWave exposes zero capture endpoints.\n}', adapter_text)
if capture_removed != 1:
    raise SystemExit('Pinned capture function mismatch')
adapter.write_text(adapter_text, encoding='utf-8')
for project in a.output.rglob('*.vcxproj'):
    text = project.read_text(encoding='utf-8-sig')
    text = text.replace(';SYSVAD_BTH_BYPASS', '').replace(';SYSVAD_USB_SIDEBAND', '')
    if project.name == 'TabletAudioSample.vcxproj':
        text = text.replace('<TargetName>TabletAudioSample</TargetName>', '<TargetName>CatchWaveAudio</TargetName>')
        text = text.replace('Include="*.inx"', 'Include="CatchWaveAudio.inx"')
    project.write_text(text, encoding='utf-8')
# The pinned sample leaves eight sideband branches outside its feature guards.
# With both sideband features disabled, keep the existing ordinary virtual-device branch.
engine = a.output / 'EndpointsCommon/MiniportAudioEngineNode.cpp'
engine_text = engine.read_text(encoding='utf-8-sig')
branch = re.compile(r'(^    if \(IsSidebandDevice\(\) && m_pSidebandDevice->Is(?:Volume|Mute)Supported\(m_DeviceType\)\)\n    \{\n.*?^    \}\n    else\n)', re.M | re.S)
engine_text, guarded = branch.subn(lambda m: '#if defined(SYSVAD_BTH_BYPASS) || defined(SYSVAD_USB_SIDEBAND)\n' + m.group(1) + '#endif\n', engine_text)
if guarded != 8:
    raise SystemExit('Pinned sideband branch mismatch')
engine.write_text(engine_text, encoding='utf-8')
shutil.copyfile(Path(__file__).with_name('CatchWaveAudio.inx'), a.output / 'TabletAudioSample/CatchWaveAudio.inx')
files = {str(path.relative_to(a.output)).replace('\\', '/'): hashlib.sha256(path.read_bytes()).hexdigest()
         for path in a.output.rglob('*') if path.is_file()}
(a.output / 'source-manifest.json').write_text(json.dumps({'upstream': COMMIT, 'status': 'prepared-not-built-not-installed', 'files': files}, indent=2), encoding='utf-8')
print(f'Prepared {len(files)} source files; one render endpoint, zero capture endpoints; no installation')
