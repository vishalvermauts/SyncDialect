package com.syncdialect.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadService : Service() {

    private val CHANNEL_ID = "ModelDownloadChannel"
    private val NOTIFICATION_ID = 1
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isDownloading = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isDownloading) {
            isDownloading = true
            startForeground(NOTIFICATION_ID, createNotification(0))
            startDownload()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress for AI model downloads"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading AI Engine")
            .setContentText(if (progress < 100) "$progress%" else "Download complete")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(progress < 100)
            .build()
    }

    private fun updateNotification(progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(progress))
    }

    private fun startDownload() {
        serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraVoice::ModelDownloadService")
            
            val modelFileName = "gemma-4-E2B-it.litertlm"
            val modelDownloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
            
            try {
                wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/)
                
                val tempFile = File(filesDir, "$modelFileName.tmp")
                var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L
                val maxRetries = 10
                var retryCount = 0
                var success = false
                var totalFileSize = 0L

                ModelDownloadManager.downloadProgress.value = 0

                while (retryCount < maxRetries && !success) {
                    try {
                        var url = URL(modelDownloadUrl)
                        var connection = url.openConnection() as HttpURLConnection
                        connection.instanceFollowRedirects = true
                        
                        if (downloadedBytes > 0) {
                            connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                        }
                        
                        connection.connect()
                        var responseCode = connection.responseCode
                        
                        var redirects = 0
                        while ((responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                                responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                                responseCode == HttpURLConnection.HTTP_SEE_OTHER) && redirects < 5) {
                            val newUrl = connection.getHeaderField("Location")
                            connection.disconnect()
                            url = URL(newUrl)
                            connection = url.openConnection() as HttpURLConnection
                            if (downloadedBytes > 0) {
                                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                            }
                            connection.connect()
                            responseCode = connection.responseCode
                            redirects++
                        }

                        if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                            if (responseCode == 416) {
                                tempFile.delete()
                                downloadedBytes = 0
                                retryCount++
                                continue
                            }
                            throw Exception("Server returned HTTP $responseCode")
                        }

                        val contentLength = connection.contentLengthLong
                        if (totalFileSize == 0L) {
                            totalFileSize = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                                downloadedBytes + contentLength
                            } else {
                                contentLength
                            }
                        }

                        val append = responseCode == HttpURLConnection.HTTP_PARTIAL
                        if (!append && downloadedBytes > 0) {
                            downloadedBytes = 0
                            tempFile.delete()
                        }

                        val input = connection.inputStream
                        val output = FileOutputStream(tempFile, append)
                        val data = ByteArray(8192)
                        var count: Int
                        var lastProgress = -1
                        var bytesDownloadedThisTry = 0L

                        while (input.read(data).also { count = it } != -1) {
                            output.write(data, 0, count)
                            downloadedBytes += count
                            bytesDownloadedThisTry += count
                            if (totalFileSize > 0) {
                                val progress = ((downloadedBytes * 100) / totalFileSize).toInt()
                                if (progress > lastProgress) {
                                    lastProgress = progress
                                    ModelDownloadManager.downloadProgress.value = progress
                                    if (progress % 5 == 0) { // Update notification every 5%
                                        updateNotification(progress)
                                    }
                                }
                            }
                        }

                        output.flush()
                        output.close()
                        input.close()
                        connection.disconnect()
                        
                        success = true

                    } catch (e: Exception) {
                        Log.e("ModelDownloadService", "Chunk error: ${e.message}")
                        val currentLength = if (tempFile.exists()) tempFile.length() else 0L
                        if (currentLength > downloadedBytes) {
                            retryCount = 0
                        } else {
                            retryCount++
                        }
                        if (retryCount >= maxRetries) throw e
                        delay(2000)
                    }
                }

                if (success) {
                    val destinationFile = File(filesDir, modelFileName)
                    tempFile.renameTo(destinationFile)
                    updateNotification(100)
                    ModelDownloadManager.downloadProgress.value = 100
                }
                
            } catch (e: Exception) {
                Log.e("ModelDownloadService", "Download failed", e)
                ModelDownloadManager.downloadError.value = e.message ?: "Download failed"
                ModelDownloadManager.downloadProgress.value = -1
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                stopForeground(true)
                stopSelf()
            }
        }
    }
}
