package org.traccar.sync.util

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.EAXBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.ECPoint
import org.traccar.sync.proto.Location
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object LocationDecryptor {

    init {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }

    fun decryptOwnerKey(sharedKey: ByteArray, encryptedOwnerKey: ByteArray): ByteArray {
        val iv = encryptedOwnerKey.copyOfRange(0, 12)
        val ciphertext = encryptedOwnerKey.copyOfRange(12, encryptedOwnerKey.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedKey, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun decryptEik(ownerKey: ByteArray, encryptedEik: ByteArray): ByteArray {
        return when (encryptedEik.size) {
            48 -> {
                val iv = encryptedEik.copyOfRange(0, 16)
                val ciphertext = encryptedEik.copyOfRange(16, encryptedEik.size)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(ownerKey, "AES"), IvParameterSpec(iv))
                cipher.doFinal(ciphertext)
            }
            60 -> {
                val iv = encryptedEik.copyOfRange(0, 12)
                val ciphertext = encryptedEik.copyOfRange(12, encryptedEik.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(ownerKey, "AES"), GCMParameterSpec(128, iv))
                cipher.doFinal(ciphertext)
            }
            else -> throw Exception("Unexpected encrypted EIK size: ${encryptedEik.size}")
        }
    }

    fun decryptLocation(
        identityKey: ByteArray,
        encryptedLocation: ByteArray,
        publicKeyRandom: ByteArray?,
        timestamp: Long,
    ): DecryptedLocation {
        val decryptedBytes = if (publicKeyRandom == null || publicKeyRandom.isEmpty()) {
            decryptOwnReport(identityKey, encryptedLocation)
        } else {
            decryptCrowdsourcedReport(identityKey, encryptedLocation, publicKeyRandom, timestamp)
        }
        val location = Location.ADAPTER.decode(decryptedBytes)
        return DecryptedLocation(
            latitude = location.latitude / 1e7,
            longitude = location.longitude / 1e7,
            altitude = location.altitude,
        )
    }

    private fun decryptOwnReport(identityKey: ByteArray, encryptedLocation: ByteArray): ByteArray {
        val key = MessageDigest.getInstance("SHA-256").digest(identityKey)
        val iv = encryptedLocation.copyOfRange(0, 12)
        val ciphertext = encryptedLocation.copyOfRange(12, encryptedLocation.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun decryptCrowdsourcedReport(
        identityKey: ByteArray,
        encryptedLocation: ByteArray,
        publicKeyRandom: ByteArray,
        timestamp: Long,
    ): ByteArray {
        val spec = ECNamedCurveTable.getParameterSpec("secp160r1")
        val curveOrder = spec.n

        // Derive receiver scalar
        val k = 10
        val maskedTimestamp = (timestamp and (0xFFFFFFFF shl k).toLong()).toInt()
        val tsBytes = ByteBuffer.allocate(4).putInt(maskedTimestamp).array()
        val block = ByteArray(32)
        for (i in 0 until 11) block[i] = 0xFF.toByte()
        block[11] = k.toByte()
        System.arraycopy(tsBytes, 0, block, 12, 4)
        for (i in 16 until 27) block[i] = 0x00
        block[27] = k.toByte()
        System.arraycopy(tsBytes, 0, block, 28, 4)

        val ecbCipher = Cipher.getInstance("AES/ECB/NoPadding")
        ecbCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(identityKey, "AES"))
        val rDash = ecbCipher.doFinal(block)

        val r = BigInteger(1, rDash).mod(curveOrder)

        // R = r * G
        val rPoint = spec.g.multiply(r).normalize()

        // Reconstruct sender point from publicKeyRandom (x-coordinate)
        val senderPoint = reconstructPoint(spec, publicKeyRandom)

        // ECDH: shared = (r * S).x
        val sharedPoint = senderPoint.multiply(r).normalize()
        val sharedX = toFixedBytes(sharedPoint.affineXCoord.toBigInteger(), 20)

        // HKDF-SHA256(shared, salt=null, info=b"", length=32)
        val derivedKey = hkdfSha256(sharedX, byteArrayOf(), byteArrayOf(), 32)

        // Nonce: R.x[12:20] || S.x[12:20]
        val rX = toFixedBytes(rPoint.affineXCoord.toBigInteger(), 20)
        val sX = toFixedBytes(senderPoint.normalize().affineXCoord.toBigInteger(), 20)
        val nonce = ByteArray(16)
        System.arraycopy(rX, 12, nonce, 0, 8)
        System.arraycopy(sX, 12, nonce, 8, 8)

        // AES-EAX-256 decrypt
        val ciphertext = encryptedLocation.copyOfRange(0, encryptedLocation.size - 16)
        val tag = encryptedLocation.copyOfRange(encryptedLocation.size - 16, encryptedLocation.size)
        val input = ciphertext + tag

        val eax = EAXBlockCipher(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(derivedKey), 128, nonce)
        eax.init(false, params)
        val output = ByteArray(eax.getOutputSize(input.size))
        var len = eax.processBytes(input, 0, input.size, output, 0)
        len += eax.doFinal(output, len)

        return output.copyOfRange(0, len)
    }

    private fun reconstructPoint(
        spec: org.bouncycastle.jce.spec.ECNamedCurveParameterSpec,
        xBytes: ByteArray,
    ): ECPoint {
        val x = BigInteger(1, xBytes)
        val curve = spec.curve
        val a = curve.a.toBigInteger()
        val b = curve.b.toBigInteger()
        val p = curve.field.characteristic

        // y^2 = x^3 + ax + b (mod p)
        val rhs = x.modPow(BigInteger.valueOf(3), p)
            .add(a.multiply(x))
            .add(b)
            .mod(p)

        val y = modSqrt(rhs, p)
        val yEven = if (y.testBit(0)) p.subtract(y) else y

        return curve.createPoint(x, yEven)
    }

    private fun modSqrt(a: BigInteger, p: BigInteger): BigInteger {
        // Tonelli-Shanks for p ≡ 3 (mod 4), which works for secp160r1
        val exp = p.add(BigInteger.ONE).shiftRight(2)
        return a.modPow(exp, p)
    }

    private fun toFixedBytes(value: BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
            else -> ByteArray(length - bytes.size) + bytes
        }
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val saltKey = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(saltKey, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        return mac.doFinal().copyOfRange(0, length)
    }
}

data class DecryptedLocation(
    val latitude: Double,
    val longitude: Double,
    val altitude: Int,
)
