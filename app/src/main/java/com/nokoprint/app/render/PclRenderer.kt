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

        // --- Enveloppe PJL (Printer Job Language) ---
        // La plupart des imprimantes de bureau (laser/jet d'encre multifonctions)
        // ignorent silencieusement du PCL brut si la tâche n'est pas explicitement
        // annoncée via PJL. UEL (Universal Exit Language) + @PJL ENTER LANGUAGE=PCL
        // indique à l'imprimante d'entrer en mode PCL pour ce job.
        writeUel(out)
        writePjlLine(out, "@PJL JOB NAME=\"NokoPrint\"")
        writePjlLine(out, "@PJL ENTER LANGUAGE=PCL")

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

        // --- Fin de tâche PJL ---
        writeUel(out)
        writePjlLine(out, "@PJL EOJ")
        writeUel(out)

        return out.toByteArray()
    }

    /** Universal Exit Language : Esc%-12345X — bascule l'imprimante en mode interprétation PJL. */
    private fun writeUel(out: ByteArrayOutputStream) {
        out.write(ESC)
        out.write("%-12345X".toByteArray(Charsets.US_ASCII))
    }

    /** Écrit une commande PJL terminée par CR LF, comme l'exige la spécification PJL. */
    private fun writePjlLine(out: ByteArrayOutputStream, line: String) {
        out.write(line.toByteArray(Charsets.US_ASCII))
        out.write(0x0D); out.write(0x0A)
    }

    private fun writeEsc(out: ByteArrayOutputStream, sequence: String) {
        out.write(ESC)
        out.write(sequence.toByteArray(Charsets.US_ASCII))
    }
}
