package app.aivoo.tools.androidprojector.server

import android.content.Context
import app.aivoo.tools.androidprojector.util.HttpsCaConfig
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

class CaServer(context: Context, port: Int = 8081) : NanoHTTPD(port) {
    private val caCertificate = HttpsCaConfig.getCaCertificate(context)

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/ca.crt") {
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "application/x-x509-ca-cert",
                ByteArrayInputStream(caCertificate),
                caCertificate.size.toLong(),
            )
            response.addHeader("Content-Disposition", "attachment; filename=android-tv-projector-ca.crt")
            return response
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }
}