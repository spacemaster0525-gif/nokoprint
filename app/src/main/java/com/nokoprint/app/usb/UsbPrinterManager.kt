package com.nokoprint.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Couche de communication générique avec une imprimante USB, basée sur la
 * spécification officielle "USB Device Class Definition for Printing
 * Devices" (classe d'interface USB = 7). Cette couche fonctionne avec
 * n'importe quelle imprimante USB qui respecte la norme, quel que soit le
 * langage d'impression qu'elle comprend ensuite (PCL, PostScript, ESC/POS...).
 *
 * Ce que cette classe NE fait PAS : elle ne connaît aucun protocole
 * propriétaire (Canon CAPT/UFRII, etc.) — seulement le transport USB
 * standard et la lecture de l'identité de l'imprimante (IEEE 1284).
 */
class UsbPrinterManager(private val context: Context) {

    companion object {
        private const val TAG = "NokoPrint/Usb"
        const val ACTION_USB_PERMISSION = "com.nokoprint.app.USB_PERMISSION"
        private const val USB_CLASS_PRINTER = UsbConstants.USB_CLASS_PRINTER // 7

        // Requête de classe standard "GET_DEVICE_ID" (USB Printer Class spec, §4.2.1)
        private const val GET_DEVICE_ID_REQUEST = 0x00
        private const val REQUEST_TYPE_CLASS_IN =
            UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_IN or 0x01 // recipient = interface
    }

    data class PrinterHandle(
        val device: UsbDevice,
        val usbInterface: UsbInterface,
        val endpointOut: UsbEndpoint
    )

    /** Liste tous les périphériques USB actuellement branchés qui déclarent la classe Imprimante. */
    fun listPrinters(): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == USB_CLASS_PRINTER
            }
        }
    }

    /** Demande la permission utilisateur pour accéder au périphérique (obligatoire sur Android). */
    fun requestPermission(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    context.unregisterReceiver(this)
                    onResult(granted)
                }
            }
        }
        val flags = PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        // A partir d'Android 13 (API 33), un receiver enregistré dynamiquement DOIT
        // préciser RECEIVER_EXPORTED ou RECEIVER_NOT_EXPORTED, sinon
        // context.registerReceiver() lève une SecurityException à l'exécution.
        // C'était le cas ici (targetSdk = 34) : la demande de permission pouvait
        // planter silencieusement avant même que la boîte de dialogue système
        // ne s'affiche. ContextCompat gère la compatibilité avec les anciennes
        // versions automatiquement.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "Demande de permission USB envoyée pour ${device.deviceName}")
        manager.requestPermission(device, pendingIntent)
    }

    /** Ouvre l'interface imprimante et récupère l'endpoint de sortie (bulk OUT). */
    fun open(device: UsbDevice): PrinterHandle? {
        val printerInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == USB_CLASS_PRINTER }
            ?: return null

        val endpointOut = (0 until printerInterface.endpointCount)
            .map { printerInterface.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            ?: return null

        return PrinterHandle(device, printerInterface, endpointOut)
    }

    /**
     * Lit la chaîne d'identité IEEE 1284 de l'imprimante (ex:
     * "MFG:HP;MDL:LaserJet P1102;CMD:PCL,PJL;"). Le champ CMD liste les
     * langages d'impression réellement supportés par l'appareil — c'est la
     * donnée la plus utile pour décider quel format envoyer.
     */
    fun readDeviceId(connection: UsbDeviceConnection, iface: UsbInterface): String? {
        if (!connection.claimInterface(iface, true)) {
            Log.e(TAG, "readDeviceId: claimInterface a échoué (interface ${iface.id})")
            return null
        }
        val buffer = ByteArray(1024)
        // wIndex = (numéro d'interface << 8) | alternate setting, selon la
        // spécification IEEE 1284 / USB Printer Class. Utiliser iface.id seul
        // ne fonctionne que par coïncidence quand l'interface imprimante est
        // l'interface n°0 d'un périphérique à interface unique.
        val wIndex = (iface.id shl 8) or iface.alternateSetting
        val length = connection.controlTransfer(
            REQUEST_TYPE_CLASS_IN,
            GET_DEVICE_ID_REQUEST,
            0, // wValue: configuration index (0 = courante)
            wIndex,
            buffer,
            buffer.size,
            5000
        )
        Log.d(TAG, "readDeviceId: controlTransfer a retourné $length octet(s)")
        if (length < 2) return null
        // Les 2 premiers octets = longueur totale (big-endian), le reste = chaîne ASCII
        return String(buffer, 2, length - 2, Charsets.US_ASCII)
    }

    /** Envoie des octets bruts déjà mis en forme (PCL, ESC/POS...) vers l'imprimante. */
    fun sendRaw(connection: UsbDeviceConnection, handle: PrinterHandle, data: ByteArray): Boolean {
        Log.d(
            TAG,
            "sendRaw: ${data.size} octet(s) à envoyer, endpoint=${handle.endpointOut.address}, " +
                "interface=${handle.usbInterface.id}, maxPacketSize=${handle.endpointOut.maxPacketSize}"
        )
        if (!connection.claimInterface(handle.usbInterface, true)) {
            Log.e(TAG, "sendRaw: claimInterface a échoué — l'interface est peut-être déjà tenue")
            return false
        }
        var offset = 0
        val chunkSize = 16 * 1024
        try {
            while (offset < data.size) {
                val len = minOf(chunkSize, data.size - offset)
                val sent = connection.bulkTransfer(handle.endpointOut, data, offset, len, 15000)
                Log.d(TAG, "sendRaw: bulkTransfer a envoyé $sent / $len octet(s) (offset=$offset)")
                if (sent < 0) {
                    Log.e(TAG, "sendRaw: bulkTransfer a échoué à l'offset $offset")
                    return false
                }
                if (sent == 0) {
                    Log.e(TAG, "sendRaw: bulkTransfer a retourné 0 — arrêt pour éviter une boucle infinie")
                    return false
                }
                offset += sent
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendRaw: exception pendant l'envoi", e)
            return false
        } finally {
            connection.releaseInterface(handle.usbInterface)
        }
        Log.d(TAG, "sendRaw: envoi terminé avec succès ($offset octet(s))")
        return true
    }

    /** Devine les langages supportés à partir du champ CMD: de la chaîne IEEE 1284. */
    fun parseSupportedLanguages(deviceId: String): Set<String> {
        val cmdField = Regex("CMD:([^;]*);?", RegexOption.IGNORE_CASE)
            .find(deviceId)?.groupValues?.get(1) ?: return emptySet()
        return cmdField.split(",").map { it.trim().uppercase() }.toSet()
    }
}
