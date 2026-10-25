package com.jsy.dayplan.ai

import android.content.Context
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 对称加密接口：生产使用 Android Keystore，测试注入内存实现。 */
interface KeyCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/** Android Keystore 管理的 AES/GCM 密钥；密钥不出安全硬件，密文出错即视为无 Key。 */
class AndroidKeyStoreCipher(private val alias: String = "dayplan_ai_master") : KeyCipher {

    private fun key(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore 要求随机 IV 由系统生成（Android 14 不接受调用方自带 IV）
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad)
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > 12) { "密文损坏" }
        val iv = blob.copyOfRange(0, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }
}

/** 测试用密钥（内存，进程内一致）。 */
class InMemoryKeyCipher : KeyCipher {
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private fun cipher() = Cipher.getInstance("AES/GCM/NoPadding")

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = cipher()
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpecWrap(secret), GCMParameterSpec(128, ByteArray(12)))
        c.updateAAD(aad)
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        val c = cipher()
        c.init(Cipher.DECRYPT_MODE, SecretKeySpecWrap(secret), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        c.updateAAD(aad)
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }

    private class SecretKeySpecWrap(bytes: ByteArray) : javax.crypto.spec.SecretKeySpec(bytes, "AES")
}

/**
 * DeepSeek API Key 的安全存储：密文写入不参与任何备份的私有文件。
 * 卸载/换机后密钥与 Keystore 一并消失，需要重新填写。
 */
class SecureKeyStore(
    private val dir: File,
    private val cipher: KeyCipher,
) {
    private val file: File get() = File(dir, "api_key.bin")
    private val aad: ByteArray get() = "dayplan-ai-key-v1".toByteArray()

    fun saveApiKey(plain: String) {
        dir.mkdirs()
        file.writeBytes(cipher.encrypt(plain.toByteArray(Charsets.UTF_8), aad))
    }

    /** 返回 null 表示未配置或密文不可解密（换机/损坏），一律当作未配置。 */
    fun loadApiKey(): String? {
        if (!file.exists()) return null
        return try {
            String(cipher.decrypt(file.readBytes(), aad), Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        fun default(context: Context, cipher: KeyCipher): SecureKeyStore =
            SecureKeyStore(File(context.noBackupFilesDir, "ai"), cipher)
    }

    /** 掩码显示：只保留前后少量字符。 */
    fun mask(plain: String): String = when {
        plain.length <= 8 -> "••••"
        else -> plain.take(3) + "••••" + plain.takeLast(4)
    }

    /** 基本格式校验（不联网）：官方 Key 形如 sk-xxxx。 */
    fun looksLikeKey(plain: String): Boolean =
        plain.startsWith("sk-") && plain.length >= 16
}
