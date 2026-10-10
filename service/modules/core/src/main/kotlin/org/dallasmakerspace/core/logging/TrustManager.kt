package org.dallasmakerspace.core.logging

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object TrustManager {
  fun disableSSLCertificateChecking() {
    val trustAllCerts =
        arrayOf<TrustManager>(
            object : X509TrustManager {
              override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                  Unit

              override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
                  Unit

              override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

    val sslContext = SSLContext.getInstance("SSL")
    sslContext.init(null, trustAllCerts, SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)

    val allHostsValid = HostnameVerifier { _, _ -> true }
    HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid)
  }
}
