package com.example.pkgenrich.utils

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer
import com.example.pkgenrich.utils.HFTokenizer
import kotlin.math.max
import kotlin.math.sqrt

class TextEmbedder private constructor(private val context: Context) {
    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var tokenizer: Map<String, Int>? = null
    private val embeddingCache = mutableMapOf<String, FloatArray?>()
    private val TAG = "TextEmbedder"
    private val MODEL_NAME = "all-MiniLM-L6-v2"
    private val MAX_SEQUENCE_LENGTH = 128
    private var hfTokenizer: HFTokenizer? = null

    companion object {
        @Volatile private var instance: TextEmbedder? = null
        @JvmStatic
        fun getInstance(context: Context): TextEmbedder {
            return instance ?: synchronized(this) {
                instance ?: TextEmbedder(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        try {
            initializeModel()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing model: ", e)
        }
    }

    private fun initializeModel() {
        try {
            val modelFile = copyAssetToInternalStorageOnce("models/$MODEL_NAME/model.onnx")
            val vocabFile = copyAssetToInternalStorageOnce("models/$MODEL_NAME/vocab.txt")
            val tokenizerFile = copyAssetToInternalStorageOnce("models/$MODEL_NAME/tokenizer.json")
            val tokenizerBytes = tokenizerFile.readBytes()
            hfTokenizer = HFTokenizer(tokenizerBytes)

            if (session == null) {
                env = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions()
                sessionOptions.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
                sessionOptions.setInterOpNumThreads(1)
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                
                try {
                    session = env?.createSession(modelFile.absolutePath, sessionOptions)
                    Log.d(TAG, "Model initialized successfully at ${modelFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create ONNX session: ${e.message}")
                    throw e
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize model: ${e.message}")
            throw e
        }
    }

    private fun copyAssetToInternalStorageOnce(assetPath: String): File {
        val outputFile = File(context.filesDir, assetPath)
        if (outputFile.exists()) {
            Log.d(TAG, "File already exists at ${outputFile.absolutePath}")
            return outputFile
        }

        try {
            // 确保父目录存在
            outputFile.parentFile?.mkdirs()
            
            // 使用 use 块确保资源正确关闭
            context.assets.open(assetPath).use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    // 使用 buffer 提高复制效率
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                    }
                    
                    // 确保所有数据都被写入
                    outputStream.flush()
                    
                    Log.d(TAG, "Successfully copied asset: $assetPath to ${outputFile.absolutePath}, size: $totalBytesRead bytes")
                }
            }
            
            // 验证文件大小
            if (outputFile.length() == 0L) {
                throw IllegalStateException("Copied file is empty: ${outputFile.absolutePath}")
            }
            
            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying asset: $assetPath", e)
            // 如果复制失败，删除可能存在的部分文件
            if (outputFile.exists()) {
                outputFile.delete()
            }
            throw e
        }
    }

    private fun loadVocabulary(vocabFile: File): Map<String, Int> {
        return vocabFile.readLines().mapIndexed { index, token ->
            token to index
        }.toMap()
    }

    private fun meanPooling(tokenEmbeddings: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        var pooledEmbeddings = FloatArray(tokenEmbeddings[0].size) { 0f }
        var validTokenCount = 0

        tokenEmbeddings
            .filterIndexed { index, _ -> attentionMask[index] == 1L }
            .forEach { token ->
                validTokenCount++
                token.forEachIndexed { j, value ->
                    pooledEmbeddings[j] += value
                }
            }

        val divisor = max(validTokenCount, 1)
        pooledEmbeddings = pooledEmbeddings.map { it / divisor }.toFloatArray()
        return pooledEmbeddings
    }

    private fun l2Normalize(embeddings: FloatArray): FloatArray {
        val norm = sqrt(embeddings.sumOf { it * it.toDouble() }).toFloat()
        return if (norm == 0f) embeddings else embeddings.map { it / norm }.toFloatArray()
    }

    private fun tokenize(text: String): Pair<LongArray, LongArray> {
        val tokenizer = hfTokenizer ?: throw IllegalStateException("HFTokenizer not initialized")
        val result = tokenizer.tokenize(text)
        return Pair(result.ids, result.attentionMask)
    }

    fun getBatchedEmbedding(texts: List<String>): List<FloatArray?> {
        try {
            if (hfTokenizer == null || session == null) throw IllegalStateException("Tokenizer or session not initialized")
            if (texts.isEmpty()) return emptyList()

            val batchSize = texts.size
            val tokenizedBatch = texts.map { hfTokenizer!!.tokenize(it) }

            val inputIdsBuffer = LongBuffer.allocate(batchSize * MAX_SEQUENCE_LENGTH)
            val attentionMaskBuffer = LongBuffer.allocate(batchSize * MAX_SEQUENCE_LENGTH)
            val tokenTypeIdsBuffer = LongBuffer.allocate(batchSize * MAX_SEQUENCE_LENGTH)

            tokenizedBatch.forEach { result ->
                val ids = result.ids
                val mask = result.attentionMask
                for (i in 0 until MAX_SEQUENCE_LENGTH) {
                    inputIdsBuffer.put(ids.getOrElse(i) { 0L })
                    attentionMaskBuffer.put(mask.getOrElse(i) { 0L })
                    tokenTypeIdsBuffer.put(0L)
                }
            }
            inputIdsBuffer.flip()
            attentionMaskBuffer.flip()
            tokenTypeIdsBuffer.flip()

            val env = this.env ?: OrtEnvironment.getEnvironment()
            val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuffer, longArrayOf(batchSize.toLong(), MAX_SEQUENCE_LENGTH.toLong()))
            val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMaskBuffer, longArrayOf(batchSize.toLong(), MAX_SEQUENCE_LENGTH.toLong()))
            val tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIdsBuffer, longArrayOf(batchSize.toLong(), MAX_SEQUENCE_LENGTH.toLong()))

            val inputNames = session?.inputNames ?: emptySet()
            val inputs = mutableMapOf<String, OnnxTensor>()
            inputs["input_ids"] = inputIdsTensor
            if (inputNames.contains("attention_mask")) {
                inputs["attention_mask"] = attentionMaskTensor
            }
            if (inputNames.contains("token_type_ids")) {
                inputs["token_type_ids"] = tokenTypeIdsTensor
            }

            val output = session?.run(inputs)
            val outputKeys = output?.map { it.key } ?: emptyList()
            Log.d(TAG, "ONNX output keys: $outputKeys")
            output?.forEach { (k, v) ->
                Log.d(TAG, "Output key: $k, value class: ${v?.javaClass}, value: $v")
            }

            val outputValue: Any? =
                output?.get("1001")?.let { (it as? OnnxValue)?.value }
                ?: output?.get("sentence_embedding")?.let { (it as? OnnxValue)?.value }
                ?: output?.get("last_hidden_state")?.let { (it as? OnnxValue)?.value }
                ?: output?.firstOrNull()?.let { (it.value as? OnnxValue)?.value }

            val finalEmbeddings = mutableListOf<FloatArray?>()
            when (outputValue) {
                is Array<*> -> {
                    if (outputValue.isNotEmpty() && outputValue[0] is Array<*>) {
                        // [batch, seq, hidden]
                        val arr = outputValue as? Array<Array<FloatArray>>
                        if (arr != null) {
                            for (i in 0 until batchSize) {
                                val singleLastHiddenState = arr[i]
                                val singleAttentionMask = tokenizedBatch[i].attentionMask
                                val pooledEmbedding = meanPooling(singleLastHiddenState, singleAttentionMask)
                                val normalizedEmbedding = l2Normalize(pooledEmbedding)
                                finalEmbeddings.add(normalizedEmbedding)
                            }
                        }
                    } else if (outputValue[0] is FloatArray) {
                        // [batch, hidden]
                        val arr = outputValue as? Array<FloatArray>
                        if (arr != null) {
                            for (i in 0 until batchSize) {
                                val normalizedEmbedding = l2Normalize(arr[i])
                                finalEmbeddings.add(normalizedEmbedding)
                            }
                        }
                    }
                }
                else -> {
                    Log.e(TAG, "ONNX output is null or has unknown type: ${outputValue?.javaClass}")
                    repeat(batchSize) { finalEmbeddings.add(null) }
                }
            }

            inputIdsTensor.close()
            attentionMaskTensor.close()
            tokenTypeIdsTensor.close()
            output?.close()

            return finalEmbeddings
        } catch (e: Exception) {
            Log.e(TAG, "Error getting batched embedding: ", e)
            return List(texts.size) { null }
        }
    }

    fun encode(text: String): FloatArray? {
        return embeddingCache[text] ?: getBatchedEmbedding(listOf(text)).first()?.also {
            embeddingCache[text] = it
        }
    }

    fun encodeBatch(labels: List<String>): List<FloatArray?> {
        val results = mutableListOf<FloatArray?>()
        val uncachedLabels = mutableListOf<String>()
        val uncachedIndices = mutableListOf<Int>()
        labels.forEachIndexed { index, label ->
            embeddingCache[label]?.let {
                results.add(it)
            } ?: run {
                results.add(null)
                uncachedLabels.add(label)
                uncachedIndices.add(index)
            }
        }
        if (uncachedLabels.isNotEmpty()) {
            val uncachedEmbeddings = getBatchedEmbedding(uncachedLabels)
            uncachedEmbeddings.forEachIndexed { i, embedding ->
                val label = uncachedLabels[i]
                embeddingCache[label] = embedding
                results[uncachedIndices[i]] = embedding
            }
        }
        return results
    }

    fun release() {
        try {
            session?.close()
            env?.close()
            session = null
            env = null
            tokenizer = null
            embeddingCache.clear()
            hfTokenizer?.close()
            hfTokenizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources: ", e)
        }
    }
} 