package com.hexsleuth.hexai

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.Base64
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.*
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.io.File
import java.io.FileOutputStream
import android.os.Handler
import android.os.Looper

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var splashOverlay: View
    private lateinit var splashGif: ImageView
    private var musicService: MusicService? = null
    private var isServiceBound = false
    private var audioPlaying = false
    private var splashHidden = false
    private var startTime = 0L
    private val SPLASH_MIN_DELAY = 20000L // Increased timing for better splash visibility

    inner class WebAppInterface {
        @JavascriptInterface
        fun onAudioStart() {
            runOnUiThread {
                if (!audioPlaying) {
                    audioPlaying = true
                    val intent = Intent(this@MainActivity, MusicService::class.java).apply {
                        action = MusicService.ACTION_START
                    }
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                    if (!isServiceBound) {
                        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                    } else {
                        musicService?.updateNotification(true)
                    }
                }
            }
        }

        @JavascriptInterface
        fun onAudioPause() {
            runOnUiThread {
                if (audioPlaying) {
                    audioPlaying = false
                    musicService?.updateNotification(playing = false)
                }
            }
        }

        @JavascriptInterface
        fun onRepeatStateChanged(isRepeating: Boolean) {
            runOnUiThread {
                musicService?.updateNotification(playing = audioPlaying, repeating = isRepeating)
            }
        }

        @JavascriptInterface
        fun onBlobDownload(base64Data: String, filename: String, mimetype: String) {
            runOnUiThread {
                try {
                    val base64 = if (base64Data.contains("base64,")) {
                        base64Data.substringAfter("base64,")
                    } else {
                        base64Data
                    }
                    val fileData = Base64.decode(base64, Base64.DEFAULT)

                    val resolvedMimeType = if (mimetype.isEmpty() || mimetype == "undefined") {
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(filename)) ?: "application/octet-stream"
                    } else {
                        mimetype
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, resolvedMimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        uri?.let {
                            contentResolver.openOutputStream(it)?.use { outputStream ->
                                outputStream.write(fileData)
                            }
                            Toast.makeText(this@MainActivity, "Saved to Downloads: $filename", Toast.LENGTH_SHORT).show()
                        } ?: throw Exception("Failed to create MediaStore entry")
                    } else {
                        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!path.exists()) path.mkdirs()
                        val file = File(path, filename)
                        FileOutputStream(file).use { it.write(fileData) }
                        MediaScannerConnection.scanFile(this@MainActivity, arrayOf(file.absolutePath), null, null)
                        Toast.makeText(this@MainActivity, "Saved to Downloads: $filename", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.LocalBinder
            musicService = binder.getService()
            musicService?.onTogglePlayPause = { togglePlayPause() }
            musicService?.onToggleRepeat = { toggleRepeat() }
            isServiceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
        }
    }

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Permissions required for background playback and downloads.", Toast.LENGTH_LONG).show()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            fileChooserCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        splashOverlay = findViewById(R.id.splashOverlay)
        splashGif = findViewById(R.id.splashGif)

        startTime = System.currentTimeMillis()
        showSplash()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainContainer)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupWebView()
        checkPermissions()
        loadWebApp()
    }

    private fun showSplash() {
        try {
            Glide.with(this)
                .asGif()
                .load("file:///android_asset/splash.gif")
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(splashGif)
        } catch (e: Exception) {
            e.printStackTrace()
            splashOverlay.visibility = View.GONE
        }
    }

    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true
        splashOverlay.animate()
            .alpha(0f)
            .setDuration(800)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                splashOverlay.visibility = View.GONE
            }
            .start()
    }

    private fun setupWebView() {
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                databaseEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_AUTO)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                    injectAudioDetectionScript()
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    val remaining = Math.max(0, SPLASH_MIN_DELAY - elapsed)
                    Handler(Looper.getMainLooper()).postDelayed({
                        hideSplash()
                    }, remaining)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url.toString()
                    if (url.startsWith("http") && !url.contains("hexsleuth") && !url.contains("localhost")) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            return true
                        } catch (e: Exception) {
                            return false
                        }
                    }
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileChooserCallback = filePathCallback
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    fileChooserLauncher.launch(intent)
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("HEX AI")
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setCancelable(false)
                        .show()
                    return true
                }
            }

            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                downloadFile(url, filename, mimetype)
            }
        }
    }

    private fun injectAudioDetectionScript() {
        val script = """
            (function() {
                if (window.__audioDetectorInstalled) return;
                window.__audioDetectorInstalled = true;
                var activeAudio = new Set();
                var isRepeating = false;
                function checkActive() {
                    var anyPlaying = activeAudio.size > 0;
                    if (anyPlaying != window.__wasPlaying) {
                        window.__wasPlaying = anyPlaying;
                        if (anyPlaying) try { Android.onAudioStart(); } catch(e) {}
                        else try { Android.onAudioPause(); } catch(e) {}
                    }
                }
                var origPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function() {
                    activeAudio.add(this);
                    if (isRepeating) this.loop = true;
                    this.autoplay = true;
                    checkActive();
                    return origPlay.apply(this, arguments);
                };
                var origPause = HTMLMediaElement.prototype.pause;
                HTMLMediaElement.prototype.pause = function() {
                    activeAudio.delete(this);
                    checkActive();
                    return origPause.apply(this, arguments);
                };
                window.togglePlayPause = function() {
                    var medias = document.querySelectorAll('audio, video');
                    medias.forEach(function(el) {
                        if (el.paused) el.play(); else el.pause();
                    });
                };
                window.toggleRepeat = function() {
                    isRepeating = !isRepeating;
                    var medias = document.querySelectorAll('audio, video');
                    medias.forEach(function(el) {
                        el.loop = isRepeating;
                    });
                    try { Android.onRepeatStateChanged(isRepeating); } catch(e) {}
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun togglePlayPause() {
        webView.evaluateJavascript("if(window.togglePlayPause) window.togglePlayPause();", null)
    }

    private fun toggleRepeat() {
        webView.evaluateJavascript("if(window.toggleRepeat) window.toggleRepeat();", null)
    }

    private fun downloadFile(url: String, filename: String, mimetype: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.startsWith("blob:", ignoreCase = true) || trimmedUrl.startsWith("data:", ignoreCase = true)) {
            val escapedUrl = trimmedUrl.replace("'", "\\'")
            val escapedFilename = filename.replace("'", "\\'")
            val escapedMimetype = mimetype.replace("'", "\\'")
            val script = """
                (function() {
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', '$escapedUrl', true);
                    xhr.responseType = 'blob';
                    xhr.onload = function() {
                        if (this.status == 200) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                Android.onBlobDownload(reader.result, '$escapedFilename', '$escapedMimetype');
                            };
                            reader.readAsDataURL(this.response);
                        }
                    };
                    xhr.onerror = function() {
                        alert('Failed to fetch blob');
                    };
                    xhr.send();
                })();
            """.trimIndent()
            webView.evaluateJavascript(script, null)
            Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val request = DownloadManager.Request(Uri.parse(trimmedUrl)).apply {
                setTitle(filename)
                setDescription("Downloading from HEX AI")
                setMimeType(mimetype)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                allowScanningByMediaScanner()
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.RECORD_AUDIO)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (perms.isNotEmpty()) requestPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun loadWebApp() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
                isServiceBound = false
            } catch (e: Exception) {}
        }
        if (audioPlaying) {
            stopService(Intent(this, MusicService::class.java))
        }
        webView.destroy()
        super.onDestroy()
    }
}
