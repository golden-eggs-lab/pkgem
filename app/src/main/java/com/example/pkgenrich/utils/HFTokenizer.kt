package com.example.pkgenrich.utils

import org.json.JSONObject

class HFTokenizer(tokenizerBytes: ByteArray) {

    data class Result(
        val ids: LongArray = longArrayOf(),
        val attentionMask: LongArray = longArrayOf()
    )

    private val tokenizerPtr: Long = createTokenizer(tokenizerBytes)

    fun tokenize(
        text: String
    ): Result {
        val output = tokenize(tokenizerPtr, text)
        // Deserialize the string
        // and read `ids` and `attention_mask` as LongArray
        val jsonObject = JSONObject(output)
        val idsArray = jsonObject.getJSONArray("ids")
        val ids = LongArray(idsArray.length())
        for (i in 0 until idsArray.length()) {
            ids[i] = (idsArray.get(i) as Int).toLong()
        }
        val attentionMaskArray = jsonObject.getJSONArray("attention_mask")
        val attentionMask = LongArray(attentionMaskArray.length())
        for (i in 0 until attentionMaskArray.length()) {
            attentionMask[i] = (attentionMaskArray.get(i) as Int).toLong()
        }
        return Result(ids, attentionMask)
    }

    fun close() {
        deleteTokenizer(tokenizerPtr)
    }

    // Given the bytes of the file `tokenizer.json`,
    // return a pointer
    private external fun createTokenizer(
        tokenizerBytes: ByteArray
    ): Long

    // Given the pointer to `Tokenizer` and the text,
    // return `ids` and `attention_mask` in JSON format
    private external fun tokenize(
        tokenizerPtr: Long,
        text: String
    ): String

    // Pass the `tokenizerPtr` which is then deallocated 
    // by the library
    private external fun deleteTokenizer(
        tokenizerPtr: Long
    )

    companion object {
        init {
            System.loadLibrary("hftokenizer")
        }
    }
}