package org.traccar.relay.push

import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object HttpEceDecryptor {

    fun decrypt(
        rawData: ByteArray,
        saltBase64: String,
        cryptoKeyBase64: String,
        privateKeyBase64: String,
        publicKeyBase64: String,
        authSecretBase64: String,
    ): ByteArray {
        val salt = base64Decode(saltBase64)
        val senderPublicKeyBytes = base64Decode(cryptoKeyBase64)
        val privateKeyDer = base64Decode(privateKeyBase64)
        val receiverPublicKeyBytes = base64Decode(publicKeyBase64)
        val authSecret = base64Decode(authSecretBase64)

        val privateKey = loadPrivateKey(privateKeyDer)
        val senderPublicKey = loadPublicKey(senderPublicKeyBytes)

        // Step 1: ECDH key agreement
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(senderPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Step 2: aesgcm key derivation with auth secret
        // auth_prk = HKDF-Extract(salt=auth_secret, ikm=shared_secret)
        val authPrk = hkdfExtract(authSecret, sharedSecret)

        // auth_ikm = HKDF-Expand(auth_prk, "Content-Encoding: auth\0", 32)
        val authInfo = "Content-Encoding: auth\u0000".toByteArray(Charsets.US_ASCII)
        val ikm = hkdfExpand(authPrk, authInfo, 32)

        // prk = HKDF-Extract(salt=salt, ikm=auth_ikm)
        val prk = hkdfExtract(salt, ikm)

        // Step 3: Build context for aesgcm
        val context = buildContext(receiverPublicKeyBytes, senderPublicKeyBytes)

        // Step 4: Derive content encryption key and nonce
        val cekInfo = "Content-Encoding: aesgcm\u0000".toByteArray(Charsets.US_ASCII) + context
        val nonceInfo = "Content-Encoding: nonce\u0000".toByteArray(Charsets.US_ASCII) + context

        val contentKey = hkdfExpand(prk, cekInfo, 16)
        val nonce = hkdfExpand(prk, nonceInfo, 12)

        // Step 5: AES-128-GCM decrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(128, nonce))
        val decrypted = cipher.doFinal(rawData)

        // Step 6: Strip 2-byte padding prefix
        return if (decrypted.size > 2) decrypted.copyOfRange(2, decrypted.size) else decrypted
    }

    private fun buildContext(receiverPublic: ByteArray, senderPublic: ByteArray): ByteArray {
        val label = "P-256\u0000".toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(label.size + 2 + receiverPublic.size + 2 + senderPublic.size)
        buf.put(label)
        buf.putShort(receiverPublic.size.toShort())
        buf.put(receiverPublic)
        buf.putShort(senderPublic.size.toShort())
        buf.put(senderPublic)
        return buf.array()
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(byteArrayOf(1))
        return mac.doFinal().copyOfRange(0, length)
    }

    private fun loadPrivateKey(der: ByteArray): PrivateKey {
        val keySpec = PKCS8EncodedKeySpec(der)
        return KeyFactory.getInstance("EC").generatePrivate(keySpec)
    }

    private fun loadPublicKey(uncompressedBytes: ByteArray): java.security.PublicKey {
        // Wrap uncompressed P-256 point (65 bytes: 0x04 + 32 + 32) in DER SubjectPublicKeyInfo
        val header = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(),
            0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01, 0x06, 0x08, 0x2A,
            0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01,
            0x07, 0x03, 0x42, 0x00
        )
        val der = header + uncompressedBytes
        val keySpec = X509EncodedKeySpec(der)
        return KeyFactory.getInstance("EC").generatePublic(keySpec)
    }

    private fun base64Decode(input: String): ByteArray {
        val padded = input + "========".substring(0, (4 - input.length % 4) % 4)
        return Base64.decode(padded, Base64.URL_SAFE)
    }
}
