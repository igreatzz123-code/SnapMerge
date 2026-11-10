package com.snapmerge.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var pickCallback: ValueCallback<Array<Uri>>? = null
    private val pickCode = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0f1115")
        window.navigationBarColor = Color.parseColor("#171a21")

        // Serve the bundled page from a secure https origin (WebCodecs needs it).
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0f1115"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                pickCallback?.onReceiveValue(null)
                pickCallback = callback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try { startActivityForResult(intent, pickCode); true } catch (e: Exception) { pickCallback = null; false }
            }
        }
        web.addJavascriptInterface(Bridge(), "SnapMergeAndroid")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != pickCode) return super.onActivityResult(requestCode, resultCode, data)
        val uris = mutableListOf<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            else data.data?.let { uris.add(it) }
        }
        pickCallback?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        pickCallback = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    /** Receives a merged video from the page in base64 chunks and saves it to Movies/SnapMerge. */
    inner class Bridge {
        private val open = mutableMapOf<String, Pair<File, String>>()
        private var counter = 0

        @JavascriptInterface
        fun begin(name: String): String {
            val id = "f${counter++}"
            val tmp = File(cacheDir, "$id.mp4")
            tmp.delete()
            open[id] = tmp to name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            return id
        }

        @JavascriptInterface
        fun chunk(id: String, b64: String) {
            val (f, _) = open[id] ?: return
            FileOutputStream(f, true).use { it.write(Base64.decode(b64, Base64.DEFAULT)) }
        }

        @JavascriptInterface
        fun end(id: String): Boolean {
            val (f, name) = open.remove(id) ?: return false
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SnapMerge")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: return false
                contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                runOnUiThread { Toast.makeText(this@MainActivity, "انحفظ في الاستديو: Movies/SnapMerge", Toast.LENGTH_SHORT).show() }
                true
            } catch (e: Exception) {
                false
            } finally {
                f.delete()
            }
        }
    }
}
