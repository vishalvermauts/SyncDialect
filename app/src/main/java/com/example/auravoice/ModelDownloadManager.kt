package com.example.auravoice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadManager(private val context: Context) {

    private val modelFileName = "gemma-4-E2B-it.litertlm"
    // Using the real HuggingFace URL provided by the user.
    private val modelDownloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    fun isModelDownloaded(): Boolean {
        val modelFile = File(context.filesDir, modelFileName)
        if (modelFile.exists()) {
            if (modelFile.length() < 1000000L) { // Less than 1MB means it's a dummy or corrupt
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
        withContext(Dispatchers.IO) {
            val destinationFile = File(context.filesDir, modelFileName)
            var connection: HttpURLConnection? = null
            
            try {
                val url = URL(modelDownloadUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e("DownloadManager", "Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
                    onComplete(false)
                    return@withContext
                }

                val fileLength = connection.contentLength
                val input = connection.inputStream
                val output = FileOutputStream(destinationFile)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                var lastProgress = 0

                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        if (progress > lastProgress) {
                            lastProgress = progress
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()
                
                withContext(Dispatchers.Main) {
                    onComplete(true)
                }

            } catch (e: Exception) {
                Log.e("DownloadManager", "Error downloading model: ${e.message}")
                withContext(Dispatchers.Main) {
                    onComplete(false)
                }
            } finally {
                connection?.disconnect()
            }
        }
    }
}
