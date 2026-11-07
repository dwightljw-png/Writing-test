package com.writingtest.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.writingtest.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.UUID
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var nativeTts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingTts: Pair<String, Float>? = null
    private var ttsEngineCandidates: List<String?> = emptyList()
    private var ttsEngineIndex = 0
    private var ttsInitGeneration = 0
    private var ttsInitFailed = false
    private var hasResumedOnce = false

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback.onReceiveValue(uris)
        fileChooserCallback = null
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingPermissionRequest ?: return@registerForActivityResult
        if (granted) grantWebPermissionRequest(request) else {
            request.deny()
            toast("카메라 권한이 거부되었습니다.")
        }
        pendingPermissionRequest = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val webView = binding.webView
        configureWebView(webView)
        initializeNativeTts()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl("https://appassets.androidplatform.net/assets/writing_test_android.html")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        // Retry after the user installs Korean voices in Android settings.
        if (hasResumedOnce && ttsInitFailed) initializeNativeTts()
        hasResumedOnce = true
    }

    override fun onPause() {
        pendingTts = null
        nativeTts?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        pendingTts = null
        nativeTts?.stop()
        nativeTts?.shutdown()
        nativeTts = null
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null
        binding.webView.apply {
            removeJavascriptInterface("AndroidApp")
            destroy()
        }
        super.onDestroy()
    }

    @SuppressLint("JavascriptInterface")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.host == "appassets.androidplatform.net") return false
                if (uri.scheme == "https" || uri.scheme == "http") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    catch (_: Exception) { toast("링크를 열 수 없습니다.") }
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean =
                super.onConsoleMessage(consoleMessage)

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    pickImageLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.fileChooserCallback = null
                    toast("파일 선택기를 열 수 없습니다.")
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) grantWebPermissionRequest(request)
                        else {
                            pendingPermissionRequest = request
                            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    } else request.deny()
                }
            }
        }
    }

    // The embedded WebView lacks window.speechSynthesis on Android.
    // Use the Android system TTS engine without modifying the HTML dictation flow.
    private fun initializeNativeTts() {
        ttsInitFailed = false
        ttsReady = false
        ttsEngineIndex = 0
        // Prefer the user's system default, then try other installed engines.
        val installed = packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0
        ).mapNotNull { it.serviceInfo?.packageName }.distinct().sortedWith(
            compareBy<String> {
                when {
                    it == "com.google.android.tts" -> 0
                    it.startsWith("com.samsung") -> 1
                    else -> 2
                }
            }.thenBy { it }
        )
        ttsEngineCandidates = listOf(null) + installed
        tryNextTtsEngine()
    }

    private fun tryNextTtsEngine() {
        ttsReady = false
        nativeTts?.stop()
        nativeTts?.shutdown()
        nativeTts = null

        if (ttsEngineIndex >= ttsEngineCandidates.size) {
            ttsInitFailed = true
            pendingTts = null
            toast("한국어 TTS가 없습니다. 설정 → 일반 → 글자 읽어주기에서 한국어 음성을 설치하세요.")
            return
        }

        val enginePackage = ttsEngineCandidates[ttsEngineIndex++]
        val generation = ++ttsInitGeneration
        val listener = TextToSpeech.OnInitListener { status ->
            // An engine may invoke OnInitListener from its constructor.
            // Posting ensures nativeTts has been assigned before access.
            Handler(Looper.getMainLooper()).post {
                if (generation != ttsInitGeneration || isFinishing || isDestroyed) return@post
                val current = nativeTts ?: return@post

                // Some Samsung engines require ko-KR rather than language-only ko.
                val koreanReady = status == TextToSpeech.SUCCESS &&
                    listOf(Locale.KOREA, Locale.KOREAN).any { locale ->
                        try { current.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE }
                        catch (_: Exception) { false }
                    }
                if (koreanReady) {
                    ttsReady = true
                    ttsInitFailed = false
                    pendingTts?.let { (text, rate) ->
                        pendingTts = null
                        speakNative(text, rate)
                    }
                } else {
                    // The default engine may lack Korean even if another installed
                    // Samsung / Google engine provides it.
                    if (ttsEngineCandidates.size == 1) {
                        val additional = try { current.engines.map { it.name } }
                            catch (_: Exception) { emptyList() }
                        ttsEngineCandidates = listOf(null) + additional.distinct()
                    }
                    tryNextTtsEngine()
                }
            }
        }
        nativeTts = try {
            if (enginePackage == null) TextToSpeech(applicationContext, listener)
            else TextToSpeech(applicationContext, listener, enginePackage)
        } catch (_: Exception) {
            // Try the next installed engine, never claiming the tablet has no TTS
            // solely because one engine could not start.
            tryNextTtsEngine()
            return
        }
    }

    private fun speakNative(text: String, rate: Float) {
        val safeText = text.trim().take(1500)
        if (safeText.isEmpty()) return
        val safeRate = rate.coerceIn(0.8f, 1.15f)
        if (!ttsReady) {
            pendingTts = safeText to safeRate
            return
        }
        nativeTts?.setSpeechRate(safeRate)
        val result = nativeTts?.speak(
            safeText, TextToSpeech.QUEUE_FLUSH, null, "writing-test-" + System.nanoTime()
        )
        if (result == TextToSpeech.ERROR) toast("음성을 재생하지 못했습니다.")
    }

    // APK-only API key persistence: AndroidKeyStore-backed AES-GCM.
    // The key is never written into HTML, WebView localStorage, or study exports.
    private fun getApiEncryptionKey(): SecretKey {
        val alias = "WritingTest_Gemini_ApiKey_v1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun saveEncryptedApiKey(key: String) {
        val clean = key.trim().take(256)
        if (clean.isEmpty()) {
            deleteEncryptedApiKey()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getApiEncryptionKey())
        val bytes = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val packet = cipher.iv + bytes
        getSharedPreferences("writing_test_app_private", Context.MODE_PRIVATE)
            .edit()
            .putString("gemini_api_encrypted", Base64.encodeToString(packet, Base64.NO_WRAP))
            .commit()
    }

    private fun loadEncryptedApiKey(): String {
        val prefs = getSharedPreferences("writing_test_app_private", Context.MODE_PRIVATE)
        val packet64 = prefs.getString("gemini_api_encrypted", null) ?: return ""
        return try {
            val packet = Base64.decode(packet64, Base64.DEFAULT)
            if (packet.size < 29) return ""
            val iv = packet.copyOfRange(0, 12)
            val ciphertext = packet.copyOfRange(12, packet.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getApiEncryptionKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            // Preserve unreadable encrypted data for investigation, but never
            // silently use it or replace the correct API key with blank text.
            ""
        }
    }

    private fun deleteEncryptedApiKey() {
        getSharedPreferences("writing_test_app_private", Context.MODE_PRIVATE)
            .edit().remove("gemini_api_encrypted").commit()
    }

    private fun grantWebPermissionRequest(request: PermissionRequest) {
        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun getSavedGeminiApiKey(): String = loadEncryptedApiKey()

        @JavascriptInterface
        fun setSavedGeminiApiKey(key: String) {
            try { saveEncryptedApiKey(key) }
            catch (_: Exception) {
                runOnUiThread { toast("API 키 보관에 실패했습니다. 기기 저장소 상태를 확인하세요.") }
            }
        }

        @JavascriptInterface
        fun deleteSavedGeminiApiKey() {
            deleteEncryptedApiKey()
        }

        @JavascriptInterface
        fun ttsSpeak(text: String, rate: Double) {
            runOnUiThread { speakNative(text, rate.toFloat()) }
        }

        @JavascriptInterface
        fun ttsStop() {
            runOnUiThread {
                pendingTts = null
                nativeTts?.stop()
            }
        }

        @JavascriptInterface
        fun saveBase64File(dataUrl: String, suggestedFileName: String?) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bytes = decodeDataUrl(dataUrl)
                    val rawName = suggestedFileName ?: "Writing_Test_${UUID.randomUUID()}.png"
                    val name = sanitizeFileName(rawName)
                    val suppliedType = dataUrl.substringAfter("data:", "").substringBefore(";", "")
                    val mime = when (suppliedType) {
                        "image/png", "image/jpeg", "application/pdf", "application/json" -> suppliedType
                        else -> when {
                            name.endsWith(".png", true) -> "image/png"
                            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
                            name.endsWith(".pdf", true) -> "application/pdf"
                            name.endsWith(".artstudio", true) -> "application/json"
                            else -> "application/octet-stream"
                        }
                    }
                    val image = mime.startsWith("image/")
                    val location = if (image) Environment.DIRECTORY_PICTURES + "/Writing_Test"
                                   else Environment.DIRECTORY_DOWNLOADS + "/Writing_Test"
                    val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                     else MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    val resolver = contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, location)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(collection, values)
                        ?: throw IllegalStateException("파일 저장 위치를 만들 수 없습니다")
                    try {
                        resolver.openOutputStream(uri)?.use { out: OutputStream ->
                            out.write(bytes)
                        } ?: throw IllegalStateException("파일을 열 수 없습니다")
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                    withContext(Dispatchers.Main) {
                        toast("파일 저장 완료: ${if (image) "Pictures" else "Downloads"}/Writing_Test")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { toast("파일 저장 실패: ${e.message}") }
                }
            }
        }

        @JavascriptInterface
        fun printWebView(jobName: String?, landscape: Boolean) {
            runOnUiThread {
                try {
                    val name = jobName?.takeIf { it.isNotBlank() } ?: "Writing Test"
                    val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                    val adapter = binding.webView.createPrintDocumentAdapter(name)
                    val attributes = PrintAttributes.Builder()
                        .setMediaSize(
                            if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape()
                            else PrintAttributes.MediaSize.ISO_A4.asPortrait()
                        )
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .build()
                    printManager.print(name, adapter, attributes)
                } catch (e: Exception) {
                    toast("인쇄 실패: ${e.message}")
                }
            }
        }
    }

    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val comma = dataUrl.indexOf(',')
        require(comma >= 0) { "잘못된 data URL" }
        return android.util.Base64.decode(
            dataUrl.substring(comma + 1), android.util.Base64.DEFAULT
        )
    }

    private fun sanitizeFileName(input: String): String {
        val cleaned = input.replace(Regex("[^A-Za-z0-9가-힣._-]"), "_")
            .take(110)
        return cleaned.ifBlank { "Writing_Test_${UUID.randomUUID()}.png" }
    }
}
