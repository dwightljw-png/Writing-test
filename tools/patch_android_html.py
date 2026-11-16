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
native_speak = """function speak(str){
 if(window.AndroidApp&&typeof window.AndroidApp.ttsSpeak==='function'){
  let base=Number(db.settings.rate);if(!Number.isFinite(base))base=.95;
  base=Math.max(.8,Math.min(1.15,base));
  window.AndroidApp.ttsSpeak(String(str),base);
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
# Insert into the existing last script block to preserve the tested
# 3-script structure in the original single-file app.
final_script_end = "</script>\n\n</body></html>"
if html.count(final_script_end) != 1:
    raise SystemExit("Unexpected document ending")
key_persistence = key_persistence.replace("<script>", "", 1).replace("</script>", "", 1)
html = html.replace(final_script_end, key_persistence + final_script_end, 1)


# Handwriting eraser: applies to the APK-bundled v2.1 HTML only.
def patch_erase(old,new,label):
    global html
    n=html.count(old)
    if n!=1:
        raise SystemExit("Handwriting patch anchor mismatch for %s: %s"%(label,n))
    html=html.replace(old,new,1)

patch_erase('<button id="undoBtn" class="btn soft">',
'<button type="button" id="eraserBtn" class="btn soft" aria-pressed="false" title="눌러서 지우개 선택 · 펜 옆 버튼을 누르는 동안에도 지우개">🧽 지우개</button><button id="undoBtn" class="btn soft">','eraser button')
patch_erase('.paper canvas{display:block;width:100%;height:100%;touch-action:none;cursor:crosshair}',
'''.paper canvas{display:block;width:100%;height:100%;touch-action:none;cursor:crosshair}
#eraserBtn.eraserOn{border-color:#2563eb;background:#dbeafe;color:#1e40af;box-shadow:inset 0 0 0 1px #60a5fa}
body.cyber #eraserBtn.eraserOn{background:linear-gradient(180deg,#093c48,#081a2e);border-color:#00e8ff;color:#eaffff;box-shadow:inset 0 0 15px rgba(0,232,255,.38),0 0 7px #00e8ff,0 0 18px rgba(0,232,255,.5)}''',
'eraser style')

original_ink = """function repaint(){ctx.clearRect(0,0,drawSize.w,drawSize.h);for(const stroke of strokes)paintStroke(stroke);if(currentStroke)paintStroke(currentStroke)}
function point(e){let r=canvas.getBoundingClientRect();return [Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),Math.max(0,Math.min(1,(e.clientY-r.top)/r.height))]}
canvas.addEventListener('pointerdown',e=>{if(!session||$('resultArea').classList.contains('hidden')===false)return;e.preventDefault();currentStroke=[point(e)];try{canvas.setPointerCapture(e.pointerId)}catch(_){}repaint()});
canvas.addEventListener('pointermove',e=>{if(!currentStroke)return;e.preventDefault();currentStroke.push(point(e));repaint()});
function finishStroke(){if(currentStroke){strokes.push(currentStroke);currentStroke=null;repaint()}}
canvas.addEventListener('pointerup',finishStroke);canvas.addEventListener('pointercancel',finishStroke);window.addEventListener('resize',resizeCanvas);"""
new_ink = """function repaint(){ctx.clearRect(0,0,drawSize.w,drawSize.h);for(const stroke of strokes)paintStroke(stroke);if(currentStroke)paintStroke(currentStroke)}
function point(e){let r=canvas.getBoundingClientRect();return [Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),Math.max(0,Math.min(1,(e.clientY-r.top)/r.height))]}
// The eraser removes real normalized stroke samples; OCR uses these cleaned strokes.
let eraserSelected=false,eraserPointer=false,inkPointer=null,lastErase=null,inkHistory=[];
function rememberInk(){inkHistory.push(strokes.map(line=>line.map(pt=>pt.slice())));if(inkHistory.length>24)inkHistory.shift()}
function setEraser(on){eraserSelected=!!on;const b=$('eraserBtn');b.classList.toggle('eraserOn',eraserSelected);b.setAttribute('aria-pressed',String(eraserSelected));b.textContent=eraserSelected?'✏️ 펜으로 쓰기':'🧽 지우개';canvas.style.cursor=eraserSelected?'cell':'crosshair'}
function stylusEraser(e){
 try{if(window.AndroidApp&&typeof AndroidApp.isStylusEraserActive==='function'&&AndroidApp.isStylusEraserActive())return true}catch(_){}
 if(e.pointerType!=='pen')return false;return e.button===5||e.button===2||!!(e.buttons&(2|32|64))}
function eraseAt(pt){const w=drawSize.w,h=drawSize.h;if(!w||!h)return false;
 const rr=19*19,px=pt[0]*w,py=pt[1]*h;let altered=false,next=[];
 for(const stroke of strokes){if(!stroke.length)continue;
  let hit=false;
  for(let i=0;i<stroke.length;i++){
   const a=stroke[i],b=stroke[Math.min(i+1,stroke.length-1)];const ax=a[0]*w,ay=a[1]*h,bx=b[0]*w,by=b[1]*h;
   const dx=bx-ax,dy=by-ay,t=Math.max(0,Math.min(1,((px-ax)*dx+(py-ay)*dy)/(dx*dx+dy*dy||1)));
   if((px-ax-t*dx)**2+(py-ay-t*dy)**2<=rr){hit=true;break}
  }
  if(!hit){next.push(stroke);continue}
  altered=true;let fragment=[];
  const keep=()=>{if(fragment.length){next.push(fragment);fragment=[]}};
  if(stroke.length===1)continue;
  for(let i=0;i<stroke.length-1;i++){
   const a=stroke[i],b=stroke[i+1];let segPx=Math.hypot((b[0]-a[0])*w,(b[1]-a[1])*h);
   let n=Math.max(1,Math.ceil(segPx/5));
   for(let j=(i===0?0:1);j<=n;j++){
    const t=j/n,x=a[0]+(b[0]-a[0])*t,y=a[1]+(b[1]-a[1])*t;
    if(((x*w-px)**2+(y*h-py)**2)<=rr)keep();
    else fragment.push([x,y]);
   }
  }
  keep();
 }
 if(altered){strokes=next;repaint()}return altered
}
canvas.addEventListener('contextmenu',e=>e.preventDefault());
canvas.addEventListener('pointerdown',e=>{if(!session||$('resultArea').classList.contains('hidden')===false)return;
 e.preventDefault();if(inkPointer!==null)return;inkPointer=e.pointerId;eraserPointer=eraserSelected||stylusEraser(e);
 rememberInk();const p=point(e);lastErase=p;
 if(eraserPointer){currentStroke=null;eraseAt(p)}else{currentStroke=[p];repaint()}
 try{canvas.setPointerCapture(e.pointerId)}catch(_){}
});
canvas.addEventListener('pointermove',e=>{if(inkPointer!==e.pointerId)return;e.preventDefault();const p=point(e);
 if(eraserPointer){if(!lastErase||Math.hypot((p[0]-lastErase[0])*drawSize.w,(p[1]-lastErase[1])*drawSize.h)>2){
  const old=lastErase||p,dist=Math.hypot((p[0]-old[0])*drawSize.w,(p[1]-old[1])*drawSize.h),n=Math.max(1,Math.ceil(dist/9));
  for(let i=1;i<=n;i++)eraseAt([old[0]+(p[0]-old[0])*i/n,old[1]+(p[1]-old[1])*i/n]);lastErase=p}
 }else if(currentStroke){currentStroke.push(p);repaint()}
});
function finishStroke(e){if(e&&inkPointer!==e.pointerId)return;
 if(currentStroke){strokes.push(currentStroke);currentStroke=null;repaint()}
 inkPointer=null;eraserPointer=false;lastErase=null;
}
canvas.addEventListener('pointerup',finishStroke);canvas.addEventListener('pointercancel',finishStroke);window.addEventListener('resize',resizeCanvas);"""
patch_erase(original_ink,new_ink,'pointer drawing/erasing')
patch_erase("strokes=session.autoHand?(session.handDrafts[session.at]||[]).map(line=>line.map(p=>p.slice())):[];currentStroke=null;",
"strokes=session.autoHand?(session.handDrafts[session.at]||[]).map(line=>line.map(p=>p.slice())):[];currentStroke=null;inkPointer=null;eraserPointer=false;inkHistory=[];",'question reset')
patch_erase("$('undoBtn').addEventListener('click',()=>{strokes.pop();repaint()});",
"$('eraserBtn').addEventListener('click',()=>setEraser(!eraserSelected));$('undoBtn').addEventListener('click',()=>{if(inkHistory.length)strokes=inkHistory.pop();else strokes.pop();repaint()});",'undo and toggle')
patch_erase("strokes=[];repaint()}});$('checkBtn')",
"rememberInk();strokes=[];repaint()}});$('checkBtn')",'clear undo')
patch_erase("u.rate=slow?Math.max(.8,Math.min(.9,base*.9)):base;",
"u.rate=slow?Math.max(.7,Math.min(.82,base*.78)):base;",'browser slow speed')

patch_erase("strokes=[];currentStroke=null;repaint();$('typeAnswer')",
"strokes=[];currentStroke=null;inkHistory=[];repaint();$('typeAnswer')",
'retry clears ink undo history')


# v2.1.3: The separate slow button has no audible effect on tested Android
# devices; remove it from both exam and game, including its dead listeners.
patch_erase('<button id="gameSlow" class="btn soft" type="button">🐢 천천히</button>','','game slow button')
patch_erase('<button id="slowBtn" class="btn secondary big">🐢 천천히</button>','','exam slow button')
patch_erase(" $('gameSlow').addEventListener('click',()=>{if(game)speak(game.items[game.at][0],true)});\n",'','game slow handler')
patch_erase("$('slowBtn').addEventListener('click',()=>speak(currentItem()[0],true));",'','exam slow handler')
patch_erase(" // The old 0.75 multiplier slurred some engines; keep slow playback clear.\n u.rate=slow?Math.max(.7,Math.min(.82,base*.78)):base;",
" u.rate=base;",'browser speech rate')
patch_erase('문장을 다시 듣거나 천천히 들을 수 있어요.','문장을 다시 듣고 천천히 써 보세요.','speech hint')
patch_erase('듣기를 다시 누르거나 천천히 들을 수 있어요.','필요하면 다시 듣기를 누르세요.','paper hint')
patch_erase('‘천천히’는 발음이 지나치게 늘어지지 않도록 조절했습니다. ','','speech notice')

# Remove hardware-specific stylus barrel-button workarounds. They did not
# work with the actual Lenovo tablet; keep the reliable touch/pen eraser.
patch_erase("function stylusEraser(e){\n try{if(window.AndroidApp&&typeof AndroidApp.isStylusEraserActive==='function'&&AndroidApp.isStylusEraserActive())return true}catch(_){}\n if(e.pointerType!=='pen')return false;return e.button===5||e.button===2||!!(e.buttons&(2|32|64))}\n",'','remove stylus function')
patch_erase('eraserPointer=eraserSelected||stylusEraser(e);','eraserPointer=eraserSelected;','remove pen barrel handling')
patch_erase('title="눌러서 지우개 선택 · 펜 옆 버튼을 누르는 동안에도 지우개"','title="한 번 지우고 펜을 떼면 자동으로 연필로 돌아옵니다"','eraser tooltip')
patch_erase(
" if(currentStroke){strokes.push(currentStroke);currentStroke=null;repaint()}\n inkPointer=null;eraserPointer=false;lastErase=null;",
" if(currentStroke){strokes.push(currentStroke);currentStroke=null;repaint()}\n if(eraserPointer&&eraserSelected)setEraser(false); // Pen-up returns to writing.\n inkPointer=null;eraserPointer=false;lastErase=null;",
'auto pen after eraser gesture')

# Keep UI/application version consistent with the generated APK and HTML.
html = html.replace('Writing Test v2.1', 'Writing Test v2.1.3').replace(
    'CYBER WRITING v2.1','CYBER WRITING v2.1.3')

path.write_text(html, encoding="utf-8")
print("Android Writing Test HTML patched:", path.stat().st_size)
