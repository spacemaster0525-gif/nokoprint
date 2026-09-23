package com.nokoprint.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.nokoprint.app.render.EscPosRenderer
import com.nokoprint.app.render.PclRenderer
import com.nokoprint.app.usb.UsbPrinterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NokoPrint/Main"
    }

    private lateinit var usbManager: UsbPrinterManager
    private var currentDevice: UsbDevice? = null
    private var detectedLanguages: Set<String> = emptySet()
    private var selectedImageUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            findViewById<TextView>(R.id.textSelectedFile).text = "Fichier: ${uri.lastPathSegment}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = UsbPrinterManager(this)

        val textPrinterInfo = findViewById<TextView>(R.id.textPrinterInfo)
        val textStatus = findViewById<TextView>(R.id.textStatus)
        val radioGroup = findViewById<RadioGroup>(R.id.radioLanguage)

        findViewById<Button>(R.id.buttonDetect).setOnClickListener {
            val printers = usbManager.listPrinters()
            if (printers.isEmpty()) {
                textPrinterInfo.text = "لم يتم العثور على طابعة USB متصلة (تأكد من كابل OTG)"
                return@setOnClickListener
            }

            val device = printers.first()
            currentDevice = device

            usbManager.requestPermission(device) { granted ->
                if (!granted) {
                    textPrinterInfo.text = "تم رفض إذن الوصول إلى USB"
                    return@requestPermission
                }

                val androidUsbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val connection = androidUsbManager.openDevice(device)
                val handle = usbManager.open(device)

                if (connection == null || handle == null) {
                    textPrinterInfo.text = "تعذر فتح اتصال مع الطابعة"
                    return@requestPermission
                }

                val deviceId = usbManager.readDeviceId(connection, handle.usbInterface)
                if (deviceId != null) {
                    detectedLanguages = usbManager.parseSupportedLanguages(deviceId)
                    textPrinterInfo.text = "الطابعة: ${device.productName ?: device.deviceName}\n" +
                        "اللغات المكتشفة: ${detectedLanguages.ifEmpty { setOf("غير معروفة") }}"
                } else {
                    detectedLanguages = emptySet()
                    textPrinterInfo.text = "الطابعة: ${device.productName ?: device.deviceName}\n" +
                        "تعذرت قراءة هوية الطابعة (Device ID) — اختر اللغة يدوياً"
                }
                connection.close()
            }
        }

        findViewById<Button>(R.id.buttonPickImage).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        findViewById<Button>(R.id.buttonPrint).setOnClickListener {
            val device = currentDevice
            val imageUri = selectedImageUri

            if (device == null) {
                textStatus.text = "اكتشف الطابعة أولاً"
                return@setOnClickListener
            }
            if (imageUri == null) {
                textStatus.text = "اختر صورة أولاً"
                return@setOnClickListener
            }

            val language = when (radioGroup.checkedRadioButtonId) {
                R.id.radioPcl -> "PCL"
                R.id.radioEscPos -> "ESCPOS"
                else -> chooseAutoLanguage()
            }

            textStatus.text = "جارٍ الإرسال عبر $language ..."

            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        if (printImage(device, imageUri, language)) "OK" else "ECHEC"
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception pendant l'impression", e)
                        "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                textStatus.text = when {
                    result == "OK" -> "تم إرسال المهمة إلى الطابعة"
                    result == "ECHEC" -> "فشل الإرسال — راجع الاتصال والإذن (شوف logcat)"
                    else -> "خطأ: $result"
                }
            }
        }
    }

    /** Choisit PCL si déclaré par la Device ID, sinon ESC/POS par défaut (cas fréquent des imprimantes de tickets). */
    private fun chooseAutoLanguage(): String {
        return when {
            detectedLanguages.any { it.contains("PCL") } -> "PCL"
            detectedLanguages.any { it.contains("ESC") || it.contains("POS") } -> "ESCPOS"
            else -> "ESCPOS" // repli raisonnable pour les petites imprimantes non identifiées
        }
    }

    private fun printImage(device: UsbDevice, imageUri: Uri, language: String): Boolean {
        val bitmap = contentResolver.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: run {
            Log.e(TAG, "printImage: impossible de décoder l'image sélectionnée")
            return false
        }
        Log.d(TAG, "printImage: image décodée ${bitmap.width}x${bitmap.height}, langage=$language")

        val payload: ByteArray = when (language) {
            "PCL" -> PclRenderer.render(bitmap)
            else -> EscPosRenderer.render(bitmap)
        }
        Log.d(TAG, "printImage: payload généré = ${payload.size} octet(s)")

        val androidUsbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (!androidUsbManager.hasPermission(device)) {
            Log.e(TAG, "printImage: permission USB non accordée pour ce device")
            return false
        }
        val connection = androidUsbManager.openDevice(device) ?: run {
            Log.e(TAG, "printImage: openDevice() a retourné null")
            return false
        }
        val handle = usbManager.open(device) ?: run {
            Log.e(TAG, "printImage: aucune interface/endpoint imprimante trouvée")
            connection.close()
            return false
        }

        val ok = usbManager.sendRaw(connection, handle, payload)
        connection.close()
        Log.d(TAG, "printImage: résultat sendRaw = $ok")
        return ok
    }
}
