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

# Android WebView does not implement browser speechSynthesis. Route only
# speech playback to the existing native AndroidApp JS bridge. This patch
# runs on the assembled APK HTML; the original v2.1 source stays unchanged.
old_speak = "function speak(str,slow=false){"
native_speak = """function speak(str,slow=false){
 if(window.AndroidApp&&typeof window.AndroidApp.ttsSpeak==='function'){
  let base=Number(db.settings.rate);if(!Number.isFinite(base))base=.95;
  base=Math.max(.8,Math.min(1.15,base));
  const rate=slow?Math.max(.8,Math.min(.9,base*.9)):base;
  window.AndroidApp.ttsSpeak(String(str),rate);
  return;
 }
"""
if html.count(old_speak) != 1:
    raise SystemExit("Expected one speak() function")
html = html.replace(old_speak, native_speak, 1)

old_stop = "function stopSpeech(){if('speechSynthesis' in window)speechSynthesis.cancel()}"
native_stop = """function stopSpeech(){
 if(window.AndroidApp&&typeof window.AndroidApp.ttsStop==='function'){
  window.AndroidApp.ttsStop();return;
 }
 if('speechSynthesis' in window)speechSynthesis.cancel();
}"""
if html.count(old_stop) != 1:
    raise SystemExit("Expected one stopSpeech() function")
html = html.replace(old_stop, native_stop, 1)

old_voice_help = " $('voiceHelp').textContent=voices.length?"
new_voice_help = """ if(window.AndroidApp&&typeof window.AndroidApp.ttsSpeak==='function'){
  $('voiceHelp').textContent='APK는 기기 기본 한국어 TTS로 읽어 줍니다. 읽기 속도를 조절할 수 있습니다.';
  return;
 }
 $('voiceHelp').textContent=voices.length?"""
if html.count(old_voice_help) != 1:
    raise SystemExit("Expected one voice help message")
html = html.replace(old_voice_help, new_voice_help, 1)


# APK only: persist the entered Gemini API key in Android's app-private
# AES-GCM storage backed by AndroidKeyStore. Browser HTML remains unchanged.
# Clearing the input deletes the securely stored key. Nothing enters backups.
api_key_hint_old = 'title="개인용 API 키. 저장되지 않으며 손글씨 이미지/PDF가 Google로 전송될 수 있습니다."'
api_key_hint_new = 'title="APK 내부에 암호화 저장됩니다. 입력칸을 비우면 저장된 키도 삭제됩니다. OCR 이미지/PDF는 Google로 전송될 수 있습니다."'
if html.count(api_key_hint_old) != 1:
    raise SystemExit("Gemini key title changed unexpectedly")
html = html.replace(api_key_hint_old, api_key_hint_new, 1)
key_persistence = """<script>
(function(){
  'use strict';
  var native = window.AndroidApp;
  var field = document.getElementById('geminiKey');
  if(!field || !native || typeof native.getSavedGeminiApiKey !== 'function') return;
  var saveTimer = null;
  function saveNow(){
    if(saveTimer !== null){clearTimeout(saveTimer);saveTimer = null;}
    var value = field.value.trim();
    try {
      if(value){native.setSavedGeminiApiKey(value);}
      else{native.deleteSavedGeminiApiKey();}
    }catch(_){}
  }
  try {
    var saved = native.getSavedGeminiApiKey();
    if(saved){
      field.value = saved;
      field.dispatchEvent(new Event('input',{bubbles:true}));
    }
  }catch(_){}
  field.addEventListener('input',function(){
    if(saveTimer !== null){clearTimeout(saveTimer);saveTimer = null;}
    if(!field.value.trim()){
      try{native.deleteSavedGeminiApiKey();}catch(_){}
      return;
    }
    saveTimer = setTimeout(saveNow,350);
  });
  field.addEventListener('change',saveNow);
  field.addEventListener('blur',saveNow);
  window.addEventListener('pagehide',saveNow);
  document.addEventListener('visibilitychange',function(){
    if(document.hidden) saveNow();
  });
})();
</script>
"""
if html.count("</body></html>") != 1:
    raise SystemExit("Unexpected document ending")
html = html.replace("</body></html>", key_persistence + "</body></html>", 1)

path.write_text(html, encoding="utf-8")
print("Android Writing Test HTML patched:", path.stat().st_size)
