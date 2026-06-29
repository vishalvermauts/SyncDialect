package com.syncdialect.app

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadManager(private val context: Context) {

    companion object {
        val downloadProgress = kotlinx.coroutines.flow.MutableStateFlow(-1)
        val downloadError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }

    private val modelFileName = "gemma-4-E2B-it.litertlm"

    fun isModelDownloaded(): Boolean {
        val modelFile = File(context.filesDir, modelFileName)
        if (modelFile.exists()) {
            val expectedSize = 2588147712L // Exactly 2.58 GB
            if (modelFile.length() != expectedSize) {
                modelFile.delete()
                return false
            }
            return true
        }
        return false
    }

    fun getModelAbsolutePath(): String {
        return File(context.filesDir, modelFileName).absolutePath
    }

    suspend fun downloadModel(
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val intent = android.content.Intent(context, ModelDownloadService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        try {
            downloadProgress.collect { progress ->
                if (progress in 0..99) {
                    withContext(Dispatchers.Main) { onProgress(progress) }
                } else if (progress == 100) {
                    withContext(Dispatchers.Main) { onComplete(true) }
                    throw kotlinx.coroutines.CancellationException("done")
                } else if (downloadError.value != null) {
                    withContext(Dispatchers.Main) { onComplete(false) }
                    throw kotlinx.coroutines.CancellationException("error")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Loop broken successfully
        }
    }

    fun cancelDownload() {
        val intent = android.content.Intent(context, ModelDownloadService::class.java)
        context.stopService(intent)
        downloadProgress.value = -1
        downloadError.value = null
    }

    fun deleteModel() {
        val modelFile = File(context.filesDir, modelFileName)
        val tempFile = File(context.filesDir, "$modelFileName.tmp")
        if (modelFile.exists()) modelFile.delete()
        if (tempFile.exists()) tempFile.delete()
        downloadProgress.value = -1
    }
}
