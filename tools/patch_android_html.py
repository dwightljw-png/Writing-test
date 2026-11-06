from pathlib import Path

path = Path("app/src/main/assets/writing_test_android.html")
html = path.read_text(encoding="utf-8-sig")

required = [
    "<title>Writing Test v2.1",
    "function download(name,body){",
    'id="importInput"',
    'id="restoreInput"',
    'id="geminiKey"',
    "gemini-3.5-flash-lite",
]
for token in required:
    if token not in html:
        raise SystemExit("Original Writing Test HTML token missing: " + token)

# A native APK has no browser download manager attached to a Blob URL.
# Redirect ONLY the existing backup-export helper to Android MediaStore.
# All dictation, lesson import, handwriting, OCR, scoring and game logic stays intact.
old = "function download(name,body){"
replacement = """function download(name,body){
 if(window.AndroidApp&&typeof window.AndroidApp.saveBase64File==='function'){
  const reader=new FileReader();
  reader.onload=()=>window.AndroidApp.saveBase64File(String(reader.result),String(name||'Writing_Test_backup.json'));
  reader.onerror=()=>alert('백업 파일 저장을 준비하지 못했어요.');
  reader.readAsDataURL(new Blob([body],{type:'application/json;charset=utf-8'}));
  return;
 }
"""
if html.count(old) != 1:
    raise SystemExit("Expected exactly one backup download helper")
html = html.replace(old, replacement, 1)
path.write_text(html, encoding="utf-8")
print("Android Writing Test HTML patched:", path.stat().st_size)
