package com.juanc.adbtv

import android.util.Base64
import dadb.AdbKeyPair
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Claves ADB sin depender de AdbKeyPair.generate/read de dadb, que usan java.util.Base64
 * (solo Android 8+) y harían fallar la app en Fire OS 5/6 y Android 7.
 *
 * Mismo formato que dadb y que adb: privada en PEM PKCS#8 ("adbkey"), pública en el formato de
 * adbd en base64 seguido de " unknown@unknown" ("adbkey.pub"). Las claves ya autorizadas siguen valiendo.
 */
object AdbKeys {
    private const val PEM_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val PEM_END = "-----END PRIVATE KEY-----"
    private const val KEY_BITS = 2048
    private const val KEY_WORDS = KEY_BITS / 32

    fun load(privateFile: File, publicFile: File): AdbKeyPair {
        if (!privateFile.exists() || !publicFile.exists()) generate(privateFile, publicFile)
        val pem = privateFile.readText()
            .replace(PEM_BEGIN, "").replace(PEM_END, "").replace(Regex("\\s+"), "")
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT)))
        // dadb envía el contenido de adbkey.pub terminado en '\0'
        return AdbKeyPair(privateKey, publicFile.readBytes() + 0)
    }

    private fun generate(privateFile: File, publicFile: File) {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.genKeyPair()
        val b64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        privateFile.writeText("$PEM_BEGIN\n${b64.chunked(64).joinToString("\n")}\n$PEM_END\n")
        val pub = Base64.encodeToString(encodePublicKey(kp.public as RSAPublicKey), Base64.NO_WRAP)
        publicFile.writeText("$pub unknown@unknown")
    }

    /**
     * Estructura RSAPublicKey de adbd (little endian):
     * len(=64) | n0inv | n[64 palabras] | rr[64 palabras] | exponente
     */
    fun encodePublicKey(key: RSAPublicKey): ByteArray {
        val r32 = BigInteger.ZERO.setBit(32)
        val n = key.modulus
        val rr = BigInteger.ZERO.setBit(KEY_BITS).modPow(BigInteger.valueOf(2), n)
        val n0inv = r32.subtract(n.remainder(r32).modInverse(r32))  // -1/n[0] mod 2^32

        val buf = ByteBuffer.allocate(4 * (3 + 2 * KEY_WORDS)).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(KEY_WORDS)
        buf.putInt(n0inv.toInt())
        fun putWords(value: BigInteger) {
            var v = value
            repeat(KEY_WORDS) {
                val (q, r) = v.divideAndRemainder(r32)
                buf.putInt(r.toInt())
                v = q
            }
        }
        putWords(n)
        putWords(rr)
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }
}
