package app.aivoo.tools.androidprojector.util

import android.content.Context
import javax.security.auth.x500.X500Principal
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

object HttpsConfig {
    private const val PASSWORD = "androidprojector"
    private const val KEY_ALIAS = "server"
    private const val STORE_FILE = "server-v2.p12"

    @Volatile private var cachedFactory: SSLServerSocketFactory? = null

    @Synchronized
    fun getSocketFactory(context: Context): SSLServerSocketFactory {
        cachedFactory?.let { return it }
        val storeFile = File(context.filesDir, STORE_FILE)
        val keyStore = if (storeFile.isFile) loadStore(storeFile) else createStore(context, storeFile)
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, PASSWORD.toCharArray())
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, null)
        return sslContext.serverSocketFactory.also { cachedFactory = it }
    }

    private fun loadStore(file: File): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { input ->
            keyStore.load(input, PASSWORD.toCharArray())
        }
        return keyStore
    }

    private fun createStore(context: Context, file: File): KeyStore {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair: KeyPair = generator.generateKeyPair()

        val now = Date()
        val notBefore = Date(now.time - 24L * 60 * 60 * 1000)
        val notAfter = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000)
        val serial = BigInteger(64, SecureRandom())
        val issuer = X500Principal("CN=Android TV Projector")
        val subject = X500Principal("CN=Android TV Projector")
        val certificateBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )
        val localIp = runCatching { NetworkUtils.getIpAddress(context) }.getOrNull()
        val names = ArrayList<GeneralName>()
        names += GeneralName(GeneralName.dNSName, "localhost")
        names += GeneralName(GeneralName.iPAddress, DEROctetString(InetAddress.getByName("127.0.0.1").address))
        if (localIp != null && localIp != "0.0.0.0") {
            names += GeneralName(
                GeneralName.iPAddress,
                DEROctetString(InetAddress.getByName(localIp).address),
            )
        }
        certificateBuilder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(names.toTypedArray()),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, PASSWORD.toCharArray())
        keyStore.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            PASSWORD.toCharArray(),
            arrayOf<Certificate>(certificate),
        )
        FileOutputStream(file).use { output ->
            keyStore.store(output, PASSWORD.toCharArray())
        }
        return keyStore
    }
}