package com.example.pkgenrich.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

object AssetUtils {
    private const val TAG = "AssetUtils"

    /**
     * Copy an asset file to internal storage
     * @param context Application context
     * @param assetPath Path to the asset file (e.g., "dataset/synthetic/amy.json")
     * @param destPath Destination path in internal storage
     * @return File object pointing to the copied file
     */
    fun copyAssetToInternalStorage(context: Context, assetPath: String, destPath: String): File {
        val destFile = File(context.filesDir, destPath)
        
        // Create parent directories if they don't exist
        destFile.parentFile?.mkdirs()
        
        try {
            if (!destFile.exists()) {
                context.assets.open(assetPath).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Successfully copied asset: $assetPath to ${destFile.absolutePath}")
            } else {
                Log.d(TAG, "File already exists: ${destFile.absolutePath}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error copying asset: $assetPath", e)
            throw e
        }
        
        return destFile
    }

    /**
     * Copy all files from an asset directory to internal storage
     * @param context Application context
     * @param assetDir Directory in assets (e.g., "dataset/synthetic")
     * @param destDir Destination directory in internal storage
     * @return List of copied files
     */
    fun copyAssetDirToInternalStorage(context: Context, assetDir: String, destDir: String): List<File> {
        val copiedFiles = mutableListOf<File>()
        
        try {
            // List all files in the asset directory
            val files = context.assets.list(assetDir) ?: return emptyList()
            
            for (file in files) {
                val assetPath = if (assetDir.isEmpty()) file else "$assetDir/$file"
                val destPath = if (destDir.isEmpty()) file else "$destDir/$file"
                
                try {
                    val copiedFile = copyAssetToInternalStorage(context, assetPath, destPath)
                    copiedFiles.add(copiedFile)
                } catch (e: IOException) {
                    Log.e(TAG, "Error copying file: $assetPath", e)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error listing assets in directory: $assetDir", e)
        }
        
        return copiedFiles
    }

    /**
     * Get the absolute path to a file in internal storage
     * @param context Application context
     * @param relativePath Path relative to internal storage
     * @return Absolute path to the file
     */
    fun getInternalStoragePath(context: Context, relativePath: String): String {
        return File(context.filesDir, relativePath).absolutePath
    }
} 