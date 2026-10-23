package com.juanc.adbtv

import dadb.AdbKeyPair
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * AdbKeys debe producir exactamente el mismo adbkey.pub que dadb: si no, los TV que ya autorizaron
 * la clave de la app volverían a pedir permiso (o rechazarían la conexión).
 */
class AdbKeysTest {

    @Test
    fun publicKeyEncodingMatchesDadb() {
        repeat(3) {
            val dir = kotlin.io.path.createTempDirectory("adbkeys").toFile()
            val priv = File(dir, "adbkey")
            val pub = File(dir, "adbkey.pub")
            AdbKeyPair.generate(priv, pub)  // implementación de dadb (en la JVM sí hay java.util.Base64)

            val der = Base64.getDecoder().decode(
                priv.readText().replace(Regex("-----[A-Z ]+-----|\\s+"), "")
            )
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
            val public = KeyFactory.getInstance("RSA")
                .generatePublic(RSAPublicKeySpec(key.modulus, key.publicExponent)) as RSAPublicKey

            val ours = Base64.getEncoder().encodeToString(AdbKeys.encodePublicKey(public)) + " unknown@unknown"
            assertEquals(pub.readText(), ours)
            dir.deleteRecursively()
        }
    }
}
