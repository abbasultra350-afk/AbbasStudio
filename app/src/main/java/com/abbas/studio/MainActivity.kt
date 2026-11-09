package com.abbas.studio

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * AbbasStudio — the web studio (React + Vite, v1.5) packaged as an Android app.
 *
 * The whole studio runs inside a WebView from local assets
 * (app/src/main/assets/www), so compression / trimming / conversion all happen
 * on-device, exactly like the web version. The native shell only adds what a
 * WebView cannot do on its own:
 *  - a video file picker (the web input type="file"),
 *  - saving processed videos to Downloads (WebView cannot download blob: URLs,
 *    so a small JS bridge streams the file to Android in chunks).
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filePathCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            addJavascriptInterface(DownloadBridge(this@MainActivity), "AndroidBridge")
            webViewClient = StudioWebViewClient()
            webChromeClient = StudioWebChromeClient()
            setDownloadListener(StudioDownloadListener(this@MainActivity))
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private inner class StudioWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // Stay inside the app for local assets; open anything else in the browser.
            return if (url.startsWith("file:///android_asset/")) {
                false
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot open external link: $url", e)
                }
                true
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            view.evaluateJavascript(DOWNLOAD_BRIDGE_JS, null)
        }
    }

    private inner class StudioWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return try {
                pickVideo.launch("video/*")
                true
            } catch (e: Exception) {
                Log.w(TAG, "File picker failed", e)
                filePathCallback = null
                false
            }
        }
    }

    private class StudioDownloadListener(private val context: Context) : DownloadListener {
        override fun onDownloadStart(
            url: String,
            userAgent: String,
            contentDisposition: String,
            mimeType: String,
            contentLength: Long
        ) {
            if (url.startsWith("blob:")) return // handled by the JS bridge instead
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .setTitle(fileName)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "AbbasStudio/$fileName"
                    )
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(context, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "Download failed: $url", e)
                Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val TAG = "AbbasStudio"

        /**
         * Intercepts the studio's download links (blob: URLs). A WebView cannot
         * download blob: URLs natively, so this reads the blob in the page,
         * slices it into 512 KB base64 chunks and streams them to Android,
         * which reassembles the file into Downloads/AbbasStudio/.
         */
        private const val DOWNLOAD_BRIDGE_JS = """(function () {
          if (window.__abbasBridgeInstalled) return;
          window.__abbasBridgeInstalled = true;
          document.addEventListener('click', function (e) {
            var a = (e.target && e.target.closest) ? e.target.closest('a[download]') : null;
            if (!a) return;
            var href = a.getAttribute('href') || '';
            if (href.indexOf('blob:') !== 0) return;
            if (typeof AndroidBridge === 'undefined') return;
            e.preventDefault();
            e.stopPropagation();
            var name = a.getAttribute('download') || 'abbas-studio-output';
            fetch(href).then(function (r) { return r.arrayBuffer(); }).then(function (buf) {
              var bytes = new Uint8Array(buf);
              var CHUNK = 512 * 1024;
              var total = Math.max(1, Math.ceil(bytes.length / CHUNK));
              AndroidBridge.beginDownload(name, total);
              for (var i = 0; i < total; i++) {
                var part = bytes.subarray(i * CHUNK, Math.min(bytes.length, (i + 1) * CHUNK));
                var bin = '';
                for (var j = 0; j < part.length; j += 8192) {
                  bin += String.fromCharCode.apply(null, part.subarray(j, j + 8192));
                }
                AndroidBridge.onChunk(i, btoa(bin));
              }
              AndroidBridge.endDownload(name, total);
            }).catch(function (err) {
              console.error('AbbasStudio download failed', err);
            });
          }, true);
        })();"""
    }
}

/**
 * Receives a file from the page in base64 chunks and saves it to
 * Downloads/AbbasStudio/ via MediaStore (Android 10+) or a plain file
 * (older versions). All methods run on a background thread.
 */
class DownloadBridge(private val context: Context) {

    private val chunks = sortedMapOf<Int, ByteArray>()

    @JavascriptInterface
    fun beginDownload(name: String, totalChunks: Int) {
        synchronized(this) {
            chunks.clear()
        }
    }

    @JavascriptInterface
    fun onChunk(index: Int, base64: String) {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        synchronized(this) { chunks[index] = bytes }
    }

    @JavascriptInterface
    fun endDownload(name: String, totalChunks: Int): String {
        val data: ByteArray = synchronized(this) {
            if (chunks.size < totalChunks) {
                Log.w("AbbasStudio", "Download incomplete: ${chunks.size}/$totalChunks chunks")
            }
            val out = ByteArrayOutputStream()
            for (i in 0 until totalChunks) {
                chunks[i]?.let { out.write(it) }
            }
            chunks.clear()
            out.toByteArray()
        }
        val saved = saveToDownloads(sanitizeFileName(name), data)
        Handler(Looper.getMainLooper()).post {
            if (saved != null) {
                Toast.makeText(context, "Saved to Downloads/AbbasStudio/$saved", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Could not save the file", Toast.LENGTH_LONG).show()
            }
        }
        return saved ?: ""
    }

    private fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return clean.ifBlank { "abbas-studio-output" }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "gif" -> "image/gif"
        "png" -> "image/png"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private fun saveToDownloads(name: String, data: ByteArray): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeFor(name))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AbbasStudio")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { it.write(data) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                name
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AbbasStudio"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { it.write(data) }
                name
            }
        } catch (e: Exception) {
            Log.w("AbbasStudio", "Saving $name failed", e)
            null
        }
    }
}
