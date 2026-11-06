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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

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

    override fun onDestroy() {
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

    private fun grantWebPermissionRequest(request: PermissionRequest) {
        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class AndroidBridge {
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
