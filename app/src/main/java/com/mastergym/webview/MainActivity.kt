package com.mastergym.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import com.mastergym.webview.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PRINTER_PREFS = "thermal_printer_prefs"
        private const val KEY_PRINTER_ADDRESS = "selected_printer_address"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
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
                fallbackToSystemPrint(content, logo)
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
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val pairedPrinters = bluetoothAdapter?.bondedDevices?.toList().orEmpty().sortedBy {
            it.name ?: it.address
        }

        if (bluetoothAdapter?.isEnabled == true && pairedPrinters.isNotEmpty()) {
            val savedAddress = getSharedPreferences(PRINTER_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PRINTER_ADDRESS, null)
            val savedPrinter = pairedPrinters.firstOrNull { it.address == savedAddress }

            if (savedPrinter != null) {
                printToBluetoothPrinter(savedPrinter, content, linkLogo)
            } else {
                showPrinterChooser(pairedPrinters, content, linkLogo)
            }
        } else {
            fallbackToSystemPrint(content, linkLogo)
        }
    }

    private fun showPrinterChooser(
        pairedPrinters: List<BluetoothDevice>,
        content: String,
        linkLogo: String
    ) {
        if (pairedPrinters.size == 1) {
            savePrinterSelection(pairedPrinters.first())
            printToBluetoothPrinter(pairedPrinters.first(), content, linkLogo)
            return
        }

        val labels = pairedPrinters.map { device ->
            val name = device.name?.takeIf { it.isNotBlank() } ?: getString(R.string.printer_unknown_name)
            "$name\n${device.address}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.printer_picker_title)
            .setItems(labels) { _, which ->
                val selectedPrinter = pairedPrinters[which]
                savePrinterSelection(selectedPrinter)
                printToBluetoothPrinter(selectedPrinter, content, linkLogo)
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                fallbackToSystemPrint(content, linkLogo)
            }
            .show()
    }

    private fun savePrinterSelection(device: BluetoothDevice) {
        getSharedPreferences(PRINTER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRINTER_ADDRESS, device.address)
            .apply()
    }

    private fun printToBluetoothPrinter(device: BluetoothDevice, content: String, linkLogo: String) {
        Toast.makeText(
            this,
            getString(R.string.printer_connecting, device.name ?: device.address),
            Toast.LENGTH_SHORT
        ).show()

        Thread {
            var fallbackMessage: String? = null
            try {
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                socket.outputStream.use { outputStream ->
                    outputStream.write(buildEscPosReceipt(content, linkLogo))
                    outputStream.flush()
                }
                socket.close()

                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.printer_sent_to_device, device.name ?: device.address),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Exception) {
                fallbackMessage = getString(R.string.printer_failed_fallback)
            }

            if (fallbackMessage != null) {
                runOnUiThread {
                    Toast.makeText(this, fallbackMessage, Toast.LENGTH_SHORT).show()
                    fallbackToSystemPrint(content, linkLogo)
                }
            }
        }.start()
    }

    private fun buildEscPosReceipt(content: String, linkLogo: String): ByteArray {
        val outputStream = ByteArrayOutputStream()
        outputStream.write(byteArrayOf(0x1B, 0x40))
        outputStream.write(byteArrayOf(0x1B, 0x61, 0x01))

        if (linkLogo.isNotBlank()) {
            loadLogoForEscPos(linkLogo)?.let { logoBytes ->
                outputStream.write(logoBytes)
                outputStream.write("\n".toByteArray(Charsets.UTF_8))
            }
        }

        outputStream.write(byteArrayOf(0x1B, 0x61, 0x00))
        outputStream.write(formatThermalText(content).toByteArray(Charsets.UTF_8))
        outputStream.write("\n\n\n".toByteArray(Charsets.UTF_8))
        return outputStream.toByteArray()
    }

    private fun formatThermalText(content: String): String {
        val normalized = content
            .replace("<br>", "<br/>", ignoreCase = true)
            .replace("</p>", "</p>\n", ignoreCase = true)
            .replace("</div>", "</div>\n", ignoreCase = true)
            .replace("</tr>", "</tr>\n", ignoreCase = true)

        val text = if (looksLikeHtml(normalized)) {
            HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        } else {
            normalized
        }

        return text.replace("\u00A0", " ").trim()
    }

    private fun loadLogoForEscPos(linkLogo: String): ByteArray? {
        return try {
            val connection = URL(linkLogo).openConnection().apply {
                connectTimeout = 10000
                readTimeout = 10000
            }

            connection.getInputStream().use { inputStream ->
                BitmapFactory.decodeStream(inputStream)?.let(::bitmapToEscPos)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapToEscPos(bitmap: Bitmap): ByteArray {
        val targetWidth = minOf(bitmap.width.coerceAtLeast(1), 384)
        val targetHeight = (bitmap.height * (targetWidth.toFloat() / bitmap.width.coerceAtLeast(1)))
            .roundToInt()
            .coerceAtLeast(1)
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val bytesPerRow = ceil(resizedBitmap.width / 8.0).toInt()
        val imageBytes = ByteArrayOutputStream()

        imageBytes.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
        imageBytes.write(byteArrayOf((bytesPerRow and 0xFF).toByte(), ((bytesPerRow shr 8) and 0xFF).toByte()))
        imageBytes.write(byteArrayOf((resizedBitmap.height and 0xFF).toByte(), ((resizedBitmap.height shr 8) and 0xFF).toByte()))

        for (y in 0 until resizedBitmap.height) {
            for (x in 0 until bytesPerRow) {
                var byteValue = 0
                for (bit in 0 until 8) {
                    val pixelX = x * 8 + bit
                    if (pixelX < resizedBitmap.width) {
                        val pixel = resizedBitmap.getPixel(pixelX, y)
                        val gray = (Color.red(pixel) * 0.299 +
                            Color.green(pixel) * 0.587 +
                            Color.blue(pixel) * 0.114).toInt()
                        if (gray < 180) {
                            byteValue = byteValue or (1 shl (7 - bit))
                        }
                    }
                }
                imageBytes.write(byteValue)
            }
        }

        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        return imageBytes.toByteArray()
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
