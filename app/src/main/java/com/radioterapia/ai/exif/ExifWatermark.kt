package com.radioterapia.ai.exif

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grava metadados de rastreabilidade nos campos EXIF padrão da imagem.
 */
object ExifWatermark {

    fun aplicar(
        context: Context,
        arquivo: File,
        nomePaciente: String,
        idSimulacao: String,
        descricao: String
    ): Boolean {
        return try {
            val exif = ExifInterface(arquivo.absolutePath)
            val hash = sha256("$nomePaciente|$idSimulacao")
            val deviceId = obterDeviceId(context)

            exif.setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                "RADIOTERAPIA_v1|sid=$idSimulacao|h=$hash|did=$deviceId"
            )
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Radioterapia App v1.0")
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER ?: "Unknown")
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL ?: "Unknown")
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, descricao)

            val agora = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            exif.setAttribute(ExifInterface.TAG_DATETIME, agora)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, agora)

            exif.saveAttributes()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    @SuppressLint("HardwareIds")
    private fun obterDeviceId(context: Context): String {
        return try {
            Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ANDROID_ID
            )?.take(12) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}

