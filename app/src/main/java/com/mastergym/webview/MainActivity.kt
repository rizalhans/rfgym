package com.mastergym.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.text.TextUtils
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.textparser.PrinterTextParserImg
import com.mastergym.webview.databinding.ActivityMainBinding
import java.io.File
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PRINTER_PREFS = "thermal_printer_prefs"
        private const val KEY_PRINTER_ADDRESS = "selected_printer_address"
    }

    private lateinit var binding: ActivityMainBinding
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null
    private var cameraImageUri: Uri? = null
    private var receiptPrintWebView: WebView? = null
    private var pendingPrintContent: String? = null
    private var pendingPrintLogo: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val permissionsGranted = result.values.all { it }
            val fileChooserParams = pendingFileChooserParams

            if (permissionsGranted && fileChooserParams != null) {
                launchFileChooser(fileChooserParams)
            } else {
                filePathCallback?.onReceiveValue(null)
                resetFileChooserState()
                Toast.makeText(this, getString(R.string.file_permission_denied), Toast.LENGTH_SHORT)
                    .show()
            }
        }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val permissionsGranted = result.values.all { it }
            val content = pendingPrintContent.orEmpty()
            val logo = pendingPrintLogo.orEmpty()
            clearPendingPrintRequest()

            if (permissionsGranted) {
                continueThermalPrint(content, logo)
            } else {
                Toast.makeText(this, getString(R.string.printer_permission_denied), Toast.LENGTH_SHORT)
                    .show()
            }
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = if (result.resultCode == Activity.RESULT_OK) {
                parseFileChooserResult(result.data)
            } else {
                null
            }

            filePathCallback?.onReceiveValue(uris)
            resetFileChooserState()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                finish()
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        binding.webView.addJavascriptInterface(WebAppBridge(), "Android")

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                super.onProgressChanged(view, newProgress)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) {
                    return false
                }

                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val missingPermissions = getMissingPermissions(fileChooserParams)
                return if (missingPermissions.isNotEmpty()) {
                    pendingFileChooserParams = fileChooserParams
                    permissionLauncher.launch(missingPermissions.toTypedArray())
                    true
                } else {
                    launchFileChooser(fileChooserParams)
                    true
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val url = uri.toString()

                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.open_link_error),
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                }
            }
        }

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            binding.webView.loadUrl(getString(R.string.webview_url))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        resetFileChooserState()
        clearPendingPrintRequest()
        receiptPrintWebView?.destroy()
        receiptPrintWebView = null

        binding.webView.apply {
            stopLoading()
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    private fun launchFileChooser(fileChooserParams: WebChromeClient.FileChooserParams) {
        pendingFileChooserParams = null
        val mimeTypes = getAcceptedMimeTypes(fileChooserParams.acceptTypes)

        val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            )
            if (mimeTypes.isNotEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            }
        }

        val initialIntents = mutableListOf<Intent>()
        if (shouldOfferCamera(fileChooserParams)) {
            createCameraIntent()?.let(initialIntents::add)
        }

        val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
            putExtra(Intent.EXTRA_TITLE, getString(R.string.file_chooser_title))
            putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
        }

        try {
            fileChooserLauncher.launch(chooserIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.file_chooser_error), Toast.LENGTH_SHORT).show()
            filePathCallback?.onReceiveValue(null)
            resetFileChooserState()
        }
    }

    private fun createCameraIntent(): Intent? {
        return try {
            val imageFile = createImageFile()
            cameraImageUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                imageFile
            )

            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.takeIf { it.resolveActivity(packageManager) != null }
        } catch (_: Exception) {
            cameraImageUri = null
            null
        }
    }

    private fun createImageFile(): File {
        val imageDir = File(cacheDir, "images").apply { mkdirs() }
        return File.createTempFile("upload_", ".jpg", imageDir)
    }

    private fun parseFileChooserResult(data: Intent?): Array<Uri>? {
        val clipData = data?.clipData
        return when {
            clipData != null -> Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
            data?.data != null -> arrayOf(data.data!!)
            cameraImageUri != null -> arrayOf(cameraImageUri!!)
            else -> null
        }
    }

    private fun getMissingPermissions(
        fileChooserParams: WebChromeClient.FileChooserParams
    ): List<String> {
        val permissions = mutableListOf<String>()

        if (shouldOfferCamera(fileChooserParams) && !hasPermission(Manifest.permission.CAMERA)) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (acceptsImages(fileChooserParams.acceptTypes)) {
            val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

            if (!hasPermission(mediaPermission)) {
                permissions.add(mediaPermission)
            }
        }

        return permissions.distinct()
    }

    private fun getMissingBluetoothPermissions(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return emptyList()
        }

        return listOf(Manifest.permission.BLUETOOTH_CONNECT)
            .filterNot(::hasPermission)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun shouldOfferCamera(
        fileChooserParams: WebChromeClient.FileChooserParams
    ): Boolean {
        return fileChooserParams.isCaptureEnabled || acceptsImages(fileChooserParams.acceptTypes)
    }

    private fun acceptsImages(acceptTypes: Array<String>): Boolean {
        val mimeTypes = getAcceptedMimeTypes(acceptTypes)
        return mimeTypes.isEmpty() || mimeTypes.any { it.startsWith("image/") || it == "*/*" }
    }

    private fun getAcceptedMimeTypes(acceptTypes: Array<String>): Array<String> {
        return acceptTypes
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { value ->
                when {
                    value.startsWith(".") -> MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(value.removePrefix(".").lowercase())

                    value.contains("/") -> value
                    else -> null
                }
            }
            .distinct()
            .toTypedArray()
    }

    private fun resetFileChooserState() {
        filePathCallback = null
        pendingFileChooserParams = null
        cameraImageUri = null
    }

    private fun clearPendingPrintRequest() {
        pendingPrintContent = null
        pendingPrintLogo = null
    }

    private inner class WebAppBridge {
        @JavascriptInterface
        fun printPage(content: String?, linklogo: String?) {
            runOnUiThread {
                startThermalPrint(content.orEmpty(), linklogo.orEmpty())
            }
        }

        @JavascriptInterface
        fun resetPrinter() {
            runOnUiThread {
                getSharedPreferences(PRINTER_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_PRINTER_ADDRESS)
                    .apply()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.printer_selection_cleared),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun startThermalPrint(content: String, linkLogo: String) {
        if (content.isBlank()) {
            Toast.makeText(this, getString(R.string.print_empty_content), Toast.LENGTH_SHORT).show()
            return
        }

        val missingPermissions = getMissingBluetoothPermissions()
        if (missingPermissions.isNotEmpty()) {
            pendingPrintContent = content
            pendingPrintLogo = linkLogo
            bluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        continueThermalPrint(content, linkLogo)
    }

    private fun continueThermalPrint(content: String, linkLogo: String) {
        val pairedPrinters = BluetoothPrintersConnections().getList()?.toList().orEmpty()

        if (pairedPrinters.isNotEmpty()) {
            val savedAddress = getSharedPreferences(PRINTER_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PRINTER_ADDRESS, null)
            showPrinterChooser(pairedPrinters, savedAddress, content, linkLogo)
        } else {
            Toast.makeText(this, getString(R.string.printer_not_available), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPrinterChooser(
        pairedPrinters: List<BluetoothConnection>,
        savedAddress: String?,
        content: String,
        linkLogo: String
    ) {
        var selectedIndex = pairedPrinters.indexOfFirst { it.device?.address == savedAddress }
            .takeIf { it >= 0 }
            ?: 0

        val labels = pairedPrinters.map { conn ->
            val name = conn.device?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.printer_unknown_name)
            val addr = conn.device?.address.orEmpty()
            val savedSuffix = if (addr == savedAddress) {
                " • ${getString(R.string.printer_saved_badge)}"
            } else {
                ""
            }
            "$name\n$addr$savedSuffix"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.printer_picker_title)
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.printer_action_print) { _, _ ->
                val selectedConn = pairedPrinters[selectedIndex]
                printToBluetoothPrinter(selectedConn, content, linkLogo)
            }
            .setNeutralButton(R.string.printer_action_save_and_print) { _, _ ->
                val selectedConn = pairedPrinters[selectedIndex]
                savePrinterSelection(selectedConn.device)
                Toast.makeText(
                    this,
                    getString(R.string.printer_saved, selectedConn.device?.name ?: selectedConn.device?.address),
                    Toast.LENGTH_SHORT
                ).show()
                printToBluetoothPrinter(selectedConn, content, linkLogo)
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.printer_selection_cancelled), Toast.LENGTH_SHORT)
                    .show()
            }
            .show()
    }

    private fun savePrinterSelection(device: BluetoothDevice?) {
        val address = device?.address ?: return
        getSharedPreferences(PRINTER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRINTER_ADDRESS, address)
            .apply()
    }

    private fun printToBluetoothPrinter(connection: BluetoothConnection, content: String, linkLogo: String) {
        val deviceLabel = connection.device?.name ?: connection.device?.address ?: ""
        Toast.makeText(
            this,
            getString(R.string.printer_connecting, deviceLabel),
            Toast.LENGTH_SHORT
        ).show()

        Thread {
            var errorMessage: String? = null
            try {
                val printer = EscPosPrinter(connection, 203, 48f, 32)

                val sb = StringBuilder()
                if (linkLogo.isNotBlank()) {
                    loadLogoBitmap(linkLogo)?.let { bitmap ->
                        val hexStr = PrinterTextParserImg.bitmapToHexadecimalString(printer, bitmap, false)
                        if (hexStr.isNotEmpty()) {
                            sb.append("[C]<img>$hexStr</img>\n")
                        }
                        bitmap.recycle()
                    }
                }

                sb.append(formatThermalText(content))
                sb.append("\n\n\n")

                printer.printFormattedTextAndCut(sb.toString())

                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.printer_sent_to_device, deviceLabel),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Exception) {
                errorMessage = getString(R.string.printer_failed_no_fallback)
            }

            if (errorMessage != null) {
                runOnUiThread {
                    Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun loadLogoBitmap(linkLogo: String): Bitmap? {
        return try {
            val connection = URL(linkLogo).openConnection().apply {
                connectTimeout = 10000
                readTimeout = 10000
            }
            connection.getInputStream().use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatThermalText(content: String): String {
        val normalized = content
            .replace("<br>", "<br/>", ignoreCase = true)
            .replace("<br/>", "\n", ignoreCase = true)
            .replace("</p>", "\n", ignoreCase = true)
            .replace("</div>", "\n", ignoreCase = true)
            .replace("</tr>", "\n", ignoreCase = true)
            .replace("</li>", "\n", ignoreCase = true)

        val containsEscPosMarkup = Regex(
            """\[(L|C|R)]|<(\/)?(b|u|font|barcode|qrcode|img)(\s+[^>]*)?>""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(normalized)

        val text = if (looksLikeHtml(normalized) && !containsEscPosMarkup) {
            HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        } else {
            normalized
        }

        return text.replace("\u00A0", " ").trimEnd()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun fallbackToSystemPrint(content: String, linkLogo: String) {
        receiptPrintWebView?.destroy()
        receiptPrintWebView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false

            var hasPrinted = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (hasPrinted) return
                    hasPrinted = true
                    triggerPrintJob(this@apply)
                }
            }

            loadDataWithBaseURL(
                getString(R.string.webview_url),
                buildPrintableHtml(content, linkLogo),
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    private fun triggerPrintJob(printWebView: WebView) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            Toast.makeText(this, getString(R.string.print_not_available), Toast.LENGTH_SHORT).show()
            return
        }

        val printAdapter = printWebView.createPrintDocumentAdapter(getString(R.string.print_job_name))
        val attributes = PrintAttributes.Builder()
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .setMediaSize(PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        printManager.print(getString(R.string.print_job_name), printAdapter, attributes)
        Toast.makeText(this, getString(R.string.print_started), Toast.LENGTH_SHORT).show()
    }

    private fun buildPrintableHtml(content: String, linkLogo: String): String {
        val safeBody = if (looksLikeHtml(content)) {
            content
        } else {
            "<pre class=\"receipt-content\">${TextUtils.htmlEncode(content)}</pre>"
        }

        val logoSection = if (linkLogo.isNotBlank()) {
            "<img class=\"logo\" src=\"${TextUtils.htmlEncode(linkLogo)}\" alt=\"logo\" />"
        } else {
            ""
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <style>
                    body {
                        margin: 0;
                        padding: 12px;
                        width: 72mm;
                        max-width: 72mm;
                        font-family: monospace;
                        font-size: 12px;
                        color: #000000;
                        background: #FFFFFF;
                    }
                    .wrapper {
                        width: 100%;
                    }
                    .logo {
                        display: block;
                        margin: 0 auto 10px auto;
                        max-width: 180px;
                        max-height: 90px;
                        object-fit: contain;
                    }
                    .receipt-content {
                        white-space: pre-wrap;
                        word-break: break-word;
                        font-family: monospace;
                        margin: 0;
                    }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                    }
                    img {
                        max-width: 100%;
                    }
                </style>
            </head>
            <body>
                <div class="wrapper">
                    $logoSection
                    $safeBody
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun looksLikeHtml(content: String): Boolean {
        return Regex("<[^>]+>").containsMatchIn(content)
    }
}
