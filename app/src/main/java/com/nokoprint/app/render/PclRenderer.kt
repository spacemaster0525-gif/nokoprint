package com.nokoprint.app.render

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Convertit une image en une page PCL5 (raster graphics non compressé),
 * langage documenté publiquement par HP et supporté par une grande partie
 * des imprimantes laser/jet d'encre de bureau (HP, Brother, etc.).
 */
object PclRenderer {

    private const val ESC = 0x1B

    fun render(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        val mono = ImageUtils.toMonochrome(bitmap)
        val widthBytes = (mono.width + 7) / 8

        // Reset imprimante
        out.write(ESC); out.write('E'.code)

        // Résolution raster : 300 dpi
        writeEsc(out, "*t300R")
        // Démarrage du mode raster, à la position courante
        writeEsc(out, "*r1A")

        var offset = 0
        for (row in 0 until mono.height) {
            // Transfert d'une ligne raster non compressée : Esc*b<n>W
            writeEsc(out, "*b${widthBytes}W")
            out.write(mono.packedBits, offset, widthBytes)
            offset += widthBytes
        }

        // Fin du mode raster
        writeEsc(out, "*rB")
        // Éjection de page
        out.write(0x0C)

        return out.toByteArray()
    }

    private fun writeEsc(out: ByteArrayOutputStream, sequence: String) {
        out.write(ESC)
        out.write(sequence.toByteArray(Charsets.US_ASCII))
    }
}
