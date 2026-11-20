package app.aivoo.tools.androidprojector.util

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.DEROctetString
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

object HttpsCaConfig {
    private const val PASSWORD = "androidprojector"
    private const val KEY_ALIAS = "server"
    private const val STORE_FILE = "server-ca-v1.p12"
    private const val CA_FILE = "ca-v1.crt"

    @Volatile private var cachedFactory: SSLServerSocketFactory? = null
    @Volatile private var cachedCa: ByteArray? = null

    @Synchronized
    fun getSocketFactory(context: Context): SSLServerSocketFactory {
        cachedFactory?.let { return it }
        ensureInitialized(context)
        val keyStore = loadStore(File(context.filesDir, STORE_FILE))
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, PASSWORD.toCharArray())
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, null)
        return sslContext.serverSocketFactory.also { cachedFactory = it }
    }

    @Synchronized
    fun getCaCertificate(context: Context): ByteArray {
        cachedCa?.let { return it }
        ensureInitialized(context)
        return cachedCa ?: ByteArray(0)
    }

    private fun ensureInitialized(context: Context) {
        val storeFile = File(context.filesDir, STORE_FILE)
        val caFile = File(context.filesDir, CA_FILE)
        if (storeFile.isFile && caFile.isFile) {
            cachedCa = caFile.readBytes()
            return
        }
        val caKeyPair = generateKeyPair()
        val caCert = createCaCertificate(caKeyPair)
        val serverKeyPair = generateKeyPair()
        val serverCert = createServerCertificate(serverKeyPair, caKeyPair, caCert, context)
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, PASSWORD.toCharArray())
        keyStore.setKeyEntry(KEY_ALIAS, serverKeyPair.private, PASSWORD.toCharArray(), arrayOf(serverCert, caCert))
        FileOutputStream(storeFile).use { output -> keyStore.store(output, PASSWORD.toCharArray()) }
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            Base64.encodeToString(caCert.encoded, Base64.NO_WRAP) +
            "\n-----END CERTIFICATE-----\n"
        caFile.writeText(pem)
        cachedCa = caFile.readBytes()
    }

    private fun loadStore(file: File): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { input -> keyStore.load(input, PASSWORD.toCharArray()) }
        return keyStore
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    private fun createCaCertificate(keyPair: KeyPair): X509Certificate {
        val now = Date()
        val notBefore = Date(now.time - 24L * 60 * 60 * 1000)
        val notAfter = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000)
        val subject = X500Principal("CN=Android TV Projector CA")
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger(64, SecureRandom()), notBefore, notAfter, subject, keyPair.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun createServerCertificate(keyPair: KeyPair, caKeyPair: KeyPair, caCert: X509Certificate, context: Context): X509Certificate {
        val now = Date()
        val notBefore = Date(now.time - 24L * 60 * 60 * 1000)
        val notAfter = Date(now.time + 5L * 365 * 24 * 60 * 60 * 1000)
        val subject = X500Principal("CN=Android TV Projector")
        val builder = JcaX509v3CertificateBuilder(caCert.subjectX500Principal, BigInteger(64, SecureRandom()), notBefore, notAfter, subject, keyPair.public)
        val names = ArrayList<GeneralName>()
        names += GeneralName(GeneralName.dNSName, "localhost")
        names += GeneralName(GeneralName.iPAddress, DEROctetString(InetAddress.getByName("127.0.0.1").address))
        val localIp = runCatching { NetworkUtils.getIpAddress(context) }.getOrNull()
        if (!localIp.isNullOrBlank() && localIp != "0.0.0.0") {
            names += GeneralName(GeneralName.iPAddress, DEROctetString(InetAddress.getByName(localIp).address))
        }
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth)))
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(caKeyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}