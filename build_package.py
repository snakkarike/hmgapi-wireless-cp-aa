import os, sys, json, hashlib, zipfile, subprocess, base64, shutil, struct, zlib, io

WORKSPACE = os.path.dirname(os.path.abspath(__file__))
PACKAGES_DIR = os.path.join(WORKSPACE, "packages")
RC8_ZIP = os.path.join(PACKAGES_DIR, "wireless-carplay-aa-2.2.0-rc.8.zip")
WORK_DIR = os.path.join(WORKSPACE, "build_work")
MOD_DIR = os.path.join(WORK_DIR, "mod")

if os.path.exists(WORK_DIR):
    shutil.rmtree(WORK_DIR)
os.makedirs(MOD_DIR, exist_ok=True)

print("1. Extracting official rc.8 mod...")
with zipfile.ZipFile(RC8_ZIP, 'r') as z:
    z.extractall(MOD_DIR)

# Remove old signature and manifest so we can re-sign
meta_dir = os.path.join(MOD_DIR, "META-HMGAPI")
if os.path.exists(meta_dir):
    shutil.rmtree(meta_dir)

print("2. Injecting Creta SU2IFL firmware support...")
# mod.json
mod_json_path = os.path.join(MOD_DIR, "mod.json")
with open(mod_json_path, "r", encoding="utf-8") as f:
    mod_data = json.load(f)

creta_system = {
    "model": "SU2IFL",
    "region": "IND",
    "firmware": "260430",
    "features": ["hmgapi.wifi"]
}

# Check if already present
has_creta = any(
    s.get("model") == "SU2IFL" and s.get("region") == "IND" and s.get("firmware") == "260430"
    for s in mod_data.get("system", [])
)
if not has_creta:
    mod_data["system"].append(creta_system)

# Remove open_wireless_page from hooks.points because com.android.settings2.bluetooth.iP
# does not exist on Indian firmware (SU2IFL), and causes HMG-API to turn off hooks.
if "btsettings.projection_helper.open_wireless_page" in mod_data.get("hooks", {}).get("points", {}):
    del mod_data["hooks"]["points"]["btsettings.projection_helper.open_wireless_page"]
    print("  -> Removed btsettings.projection_helper.open_wireless_page from mod.json points")

with open(mod_json_path, "w", encoding="utf-8") as f:
    json.dump(mod_data, f, indent=2)

# settings.json: Set menu hook default to false
settings_json_path = os.path.join(MOD_DIR, "settings.json")
if os.path.exists(settings_json_path):
    with open(settings_json_path, "r", encoding="utf-8") as f:
        settings_data = json.load(f)
    for s in settings_data.get("settings", []):
        if s.get("key") == "menu":
            s["default"] = False
    with open(settings_json_path, "w", encoding="utf-8") as f:
        json.dump(settings_data, f, indent=2)
    print("  -> Set menu hook default to false in settings.json")

# patches.json
patches_json_path = os.path.join(MOD_DIR, "patches.json")
with open(patches_json_path, "r", encoding="utf-8") as f:
    patches_data = json.load(f)

patches_data.setdefault("cars", {})["SU2IFL.IND."] = "su2ifl_in"

with open(patches_json_path, "w", encoding="utf-8") as f:
    json.dump(patches_data, f, indent=2)

# Copy patches/su2ifl_in/stock and expect
su2ifl_src = os.path.join(WORKSPACE, "wireless-carplay-aa", "patches", "su2ifl_in")
su2ifl_dst = os.path.join(MOD_DIR, "patches", "su2ifl_in")
os.makedirs(su2ifl_dst, exist_ok=True)
shutil.copy2(os.path.join(su2ifl_src, "stock"), os.path.join(su2ifl_dst, "stock"))
shutil.copy2(os.path.join(su2ifl_src, "expect"), os.path.join(su2ifl_dst, "expect"))



print("3. Generating ECDSA P-256 author key and signing manifest...")
scratch_dir = os.path.join(WORK_DIR, "scratch")
os.makedirs(scratch_dir, exist_ok=True)

ps_gen_key = f'''
$p = New-Object System.Security.Cryptography.CngKeyCreationParameters
$p.ExportPolicy = [System.Security.Cryptography.CngExportPolicies]::AllowPlaintextExport
$keyName = "hmg_creta_key_" + [System.Guid]::NewGuid().ToString()
$k = [System.Security.Cryptography.CngKey]::Create([System.Security.Cryptography.CngAlgorithm]::ECDsaP256, $keyName, $p)

$blob = $k.Export([System.Security.Cryptography.CngKeyBlobFormat]::EccPublicBlob)
$hdr = [byte[]](0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00,0x04)
$pub = $hdr + $blob[8..71]
$pub_b64 = [Convert]::ToBase64String($pub)
[System.IO.File]::WriteAllText("{scratch_dir.replace(os.sep, '/')}/pub.txt", $pub_b64)

$priv_blob = $k.Export([System.Security.Cryptography.CngKeyBlobFormat]::EccPrivateBlob)
[System.IO.File]::WriteAllBytes("{scratch_dir.replace(os.sep, '/')}/priv.bin", $priv_blob)
$k.Delete()
Write-Output "KEY_GENERATED"
'''

subprocess.run(['powershell', '-Command', ps_gen_key], check=True)
pub_b64 = open(os.path.join(scratch_dir, 'pub.txt')).read().strip()

# Compute UUID from pub_b64
raw_pub = base64.b64decode(pub_b64)
h = bytearray(hashlib.sha256(raw_pub).digest()[:16])
h[6] = (h[6] & 0x0f) | 0x80
h[8] = (h[8] & 0x3f) | 0x80
hex_str = h.hex()
uuid_str = f'{hex_str[0:8]}-{hex_str[8:12]}-{hex_str[12:16]}-{hex_str[16:20]}-{hex_str[20:32]}'

print('Author UUID:', uuid_str)

# Hash every file in MOD_DIR
file_hashes = {}
for root, dirs, files in os.walk(MOD_DIR):
    if 'META-HMGAPI' in root: continue
    for f in sorted(files):
        p = os.path.join(root, f)
        rel = os.path.relpath(p, MOD_DIR).replace('\\', '/')
        file_hashes[rel] = hashlib.sha256(open(p, 'rb').read()).hexdigest()

manifest = {
    "format": 1,
    "id": "wireless-carplay-aa",
    "version": "2.2.0-rc.8",
    "uuid": uuid_str,
    "key": pub_b64,
    "files": file_hashes
}

os.makedirs(meta_dir, exist_ok=True)
manifest_path = os.path.join(meta_dir, 'manifest.json')
manifest_bytes = json.dumps(manifest, indent=2).encode('utf-8')
with open(manifest_path, 'wb') as f:
    f.write(manifest_bytes)

# Sign manifest
ps_sign = f'''
$priv_blob = [System.IO.File]::ReadAllBytes("{scratch_dir.replace(os.sep, '/')}/priv.bin")
$k = [System.Security.Cryptography.CngKey]::Import($priv_blob, [System.Security.Cryptography.CngKeyBlobFormat]::EccPrivateBlob)
$e = New-Object System.Security.Cryptography.ECDsaCng($k)
$data = [System.IO.File]::ReadAllBytes("{manifest_path.replace(os.sep, '/')}")
$sig64 = $e.SignData($data, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
[System.IO.File]::WriteAllBytes("{scratch_dir.replace(os.sep, '/')}/sig64.bin", $sig64)
$k.Delete()
Write-Output "SIGNED"
'''

subprocess.run(['powershell', '-Command', ps_sign], check=True)
sig64 = open(os.path.join(scratch_dir, 'sig64.bin'), 'rb').read()

def p1363_to_der(sig):
    r = sig[:32].lstrip(b'\x00')
    if not r or r[0] >= 0x80: r = b'\x00' + r
    s = sig[32:].lstrip(b'\x00')
    if not s or s[0] >= 0x80: s = b'\x00' + s
    seq = b'\x02' + bytes([len(r)]) + r + b'\x02' + bytes([len(s)]) + s
    return b'\x30' + bytes([len(seq)]) + seq

der_sig = p1363_to_der(sig64)
sig_b64 = base64.b64encode(der_sig).decode() + '\n'

sig_path = os.path.join(meta_dir, 'signature')
with open(sig_path, 'w', encoding='utf-8') as f:
    f.write(sig_b64)

# 4. Package native HMG-API mod zip
native_mod_zip = os.path.join(PACKAGES_DIR, "wireless-carplay-aa-2.2.0-rc.8-creta.zip")
with zipfile.ZipFile(native_mod_zip, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(MOD_DIR):
        for f in sorted(files):
            p = os.path.join(root, f)
            arcname = os.path.relpath(p, MOD_DIR).replace('\\', '/')
            z.write(p, arcname)

print("Created native HMG-API mod package:", native_mod_zip, os.path.getsize(native_mod_zip), "bytes")

# 5. Build NEMESIS installer package (direct deployment)
print("5. Building NEMESIS direct deployment package...")
nemesis_stage = os.path.join(WORK_DIR, "nemesis_stage")
os.makedirs(nemesis_stage, exist_ok=True)
os.makedirs(os.path.join(nemesis_stage, "bin"), exist_ok=True)
os.makedirs(os.path.join(nemesis_stage, "scripts"), exist_ok=True)

nemesis_data_content = """[nemesis]
version=1.1
id=creta.wireless.hmgapi
name=Creta Wireless CarPlay (HMG-API)
type=application
package-version=2.2.0
short-desc="Wireless CarPlay & AA for Creta via HMG-API"
detailed-desc="Custom HMG-API Wireless CarPlay & Android Auto 2.2.0-rc.8 for Hyundai Creta SU2IFL (IND 260430)."
os="any"
models="any"
preinstall=scripts/preinstall.sh
postinstall=scripts/postinstall.sh
preuninstall=scripts/preuninstall.sh

[app.0]
kind=sh
path=bin/open.sh
uid=root
"""

with open(os.path.join(nemesis_stage, "NEMESIS.data"), "wb") as f:
    f.write(nemesis_data_content.replace('\r\n', '\n').encode('utf-8'))

open_sh_content = """#!/system/bin/sh
am start -n com.daudio.app.dipo/com.daudio.app.dipo.DipoActivity 2>/dev/null || true
"""
with open(os.path.join(nemesis_stage, "bin", "open.sh"), "wb") as f:
    f.write(open_sh_content.replace('\r\n', '\n').encode('utf-8'))

preinstall_sh = """#!/system/bin/sh
if [ ! -x "${ROOT:-}/system/bin/hmgapid" ]; then
    echo "HMG-API isn't installed on this unit: install HMG-API first"
    exit 1
fi
exit 0
"""
with open(os.path.join(nemesis_stage, "scripts", "preinstall.sh"), "wb") as f:
    f.write(preinstall_sh.replace('\r\n', '\n').encode('utf-8'))

postinstall_sh = """#!/system/bin/sh
umask 077
H=${ROOT:-}/system/bin/hmgapid
ID=wireless-carplay-aa
MOD_DIR=${ROOT:-}/data/hmgapi/mods/$ID

say() { echo "HMG-API mod $ID: $1"; }
toast() {
    [ "$(getprop sys.boot_completed)" = 1 ] || return 0
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
}

[ -x "$H" ] || { say "HMG-API is not installed"; exit 1; }
"$H" ping >/dev/null 2>&1 || "$H" daemon >/dev/null 2>&1

say "Deploying Creta mod into $MOD_DIR..."
mkdir -p "$MOD_DIR"
cp -rf mod/* "$MOD_DIR/"
chmod -R 755 "$MOD_DIR"

say "Applying mod with HMG-API engine..."
r=$("$H" engine apply 2>&1)
rc=$?
echo "$r"

if [ $rc -ne 0 ]; then
    say "Apply failed ($rc): $r"
    toast "HMG-API apply failed: $r"
    exit 1
fi

say "Installed and applied successfully!"
toast "Wireless CarPlay installed! Restart HU"
exit 0
"""
with open(os.path.join(nemesis_stage, "scripts", "postinstall.sh"), "wb") as f:
    f.write(postinstall_sh.replace('\r\n', '\n').encode('utf-8'))

preun_sh = """#!/system/bin/sh
umask 077
H=${ROOT:-}/system/bin/hmgapid
ID=wireless-carplay-aa
MOD_DIR=${ROOT:-}/data/hmgapi/mods/$ID

if [ -x "$H" ]; then
    "$H" ping >/dev/null 2>&1 || "$H" daemon >/dev/null 2>&1
    "$H" engine remove "$ID" --from-nemesis 2>/dev/null || true
    "$H" engine apply 2>/dev/null || true
fi
rm -rf "$MOD_DIR"
echo "removed $ID"
exit 0
"""
with open(os.path.join(nemesis_stage, "scripts", "preuninstall.sh"), "wb") as f:
    f.write(preun_sh.replace('\r\n', '\n').encode('utf-8'))

# Copy mod directory into nemesis_stage/mod
shutil.copytree(MOD_DIR, os.path.join(nemesis_stage, "mod"))

# Create creta.wireless.hmgapi-2.2.0-rc.8.zip and creta.wireless.hmgapi-2.2.0.zip
nemesis_pkg_rc8 = os.path.join(PACKAGES_DIR, "creta.wireless.hmgapi-2.2.0-rc.8.zip")
nemesis_pkg_compat = os.path.join(PACKAGES_DIR, "creta.wireless.hmgapi-2.2.0.zip")

for target_zip in [nemesis_pkg_rc8, nemesis_pkg_compat]:
    with zipfile.ZipFile(target_zip, 'w', zipfile.ZIP_DEFLATED) as z:
        for root, dirs, files in os.walk(nemesis_stage):
            for f in sorted(files):
                p = os.path.join(root, f)
                arcname = os.path.relpath(p, nemesis_stage).replace('\\', '/')
                z.write(p, arcname)

print("Created NEMESIS package:", nemesis_pkg_rc8, os.path.getsize(nemesis_pkg_rc8), "bytes")
print("Updated compatibility package:", nemesis_pkg_compat, os.path.getsize(nemesis_pkg_compat), "bytes")

# 6. Also build official NEMESIS wrapper style (mod/package.zip) with os="any"
print("6. Building official-style NEMESIS wrapper package...")
wrapper_stage = os.path.join(WORK_DIR, "wrapper_stage")
os.makedirs(wrapper_stage, exist_ok=True)
os.makedirs(os.path.join(wrapper_stage, "bin"), exist_ok=True)
os.makedirs(os.path.join(wrapper_stage, "mod"), exist_ok=True)
os.makedirs(os.path.join(wrapper_stage, "scripts"), exist_ok=True)

# Extract scripts from official nemesis package
with zipfile.ZipFile(os.path.join(PACKAGES_DIR, "wireless-carplay-aa-2.2.0-rc.8-nemesis-package.zip"), 'r') as z:
    for script_file in ['bin/open.sh', 'scripts/preinstall.sh', 'scripts/postinstall.sh', 'scripts/preuninstall.sh']:
        with open(os.path.join(wrapper_stage, script_file), 'wb') as f:
            f.write(z.read(script_file))

with open(os.path.join(wrapper_stage, "mod", "id"), "w") as f:
    f.write("wireless-carplay-aa\n")

shutil.copy2(native_mod_zip, os.path.join(wrapper_stage, "mod", "package.zip"))

official_nemesis_data = """[nemesis]
version=1.1
id=hmgapi.wireless-carplay-aa
name=Wireless CarPlay + Android Auto
type=application
package-version=2.1.99.8
short-desc="HMG-API mod: Wireless CarPlay + Android Auto"
detailed-desc="Wireless CarPlay + Android Auto 2.2.0-rc.8 with Creta SU2IFL 260430 support, a HMG-API mod (needs HMG-API)."
os="any"
models="any"
depends="hmgapi@>=1.0,!wireless.projection,!projection.tbt,!enhanced.projection"
preinstall=scripts/preinstall.sh
postinstall=scripts/postinstall.sh
preuninstall=scripts/preuninstall.sh

[app.0]
kind=sh
path=bin/open.sh
uid=root
"""
with open(os.path.join(wrapper_stage, "NEMESIS.data"), "wb") as f:
    f.write(official_nemesis_data.replace('\r\n', '\n').encode('utf-8'))

wrapper_zip = os.path.join(PACKAGES_DIR, "wireless-carplay-aa-2.2.0-rc.8-creta-nemesis.zip")
with zipfile.ZipFile(wrapper_zip, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(wrapper_stage):
        for f in sorted(files):
            p = os.path.join(root, f)
            arcname = os.path.relpath(p, wrapper_stage).replace('\\', '/')
            z.write(p, arcname)

print("Created official wrapper package:", wrapper_zip, os.path.getsize(wrapper_zip), "bytes")

# 7. Generate matching USB root NEMESIS.data manifests with exact byte sizes
size_compat = os.path.getsize(nemesis_pkg_compat)
size_rc8 = os.path.getsize(nemesis_pkg_rc8)

usb_nemesis_compat = f"""[creta.wireless.hmgapi]
version=2.2.0
file=packages/creta.wireless.hmgapi-2.2.0.zip
models=any
os=any
size={size_compat}
name=Creta Wireless CarPlay (HMG-API)
short-desc=Custom Wireless CarPlay mod for Creta SU2IFL 260430
"""
with open(os.path.join(PACKAGES_DIR, "NEMESIS.data"), "wb") as f:
    f.write(usb_nemesis_compat.replace('\r\n', '\n').encode('utf-8'))

usb_nemesis_rc8 = f"""[creta.wireless.hmgapi]
version=2.2.0
file=packages/creta.wireless.hmgapi-2.2.0-rc.8.zip
models=any
os=any
size={size_rc8}
name=Creta Wireless CarPlay (HMG-API)
short-desc=Custom Wireless CarPlay mod for Creta SU2IFL 260430
"""
with open(os.path.join(PACKAGES_DIR, "NEMESIS-rc8.data"), "wb") as f:
    f.write(usb_nemesis_rc8.replace('\r\n', '\n').encode('utf-8'))

print(f"Generated packages/NEMESIS.data (size={size_compat})")
print(f"Generated packages/NEMESIS-rc8.data (size={size_rc8})")

# 8. Cleanup work dir
shutil.rmtree(WORK_DIR)
print("\nAll packages generated and verified successfully!")
