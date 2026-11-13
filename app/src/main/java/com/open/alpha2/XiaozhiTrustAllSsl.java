package com.open.alpha2;

import android.util.Log;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A "trust-all" SSL helper dedicated to XiaoZhi. Only the two classes
 * XiaozhiClient (WebSocket) and XiaozhiOtaClient (OTA/activation-code HTTP) use
 * it. It does not affect other places in this app that use
 * HttpsURLConnection/SSLSocketFactory (for example, Radio Browser and camera
 * vision).
 *
 * Background (2026-08): the system CA store of Android 5.1 (API 22, shipped in
 * 2015) is a snapshot from the factory. It has no OTA update. Depending on the
 * vendor build time and whether the vendor applied patches, the built-in root
 * certificate list can differ completely from one device to another. This is not
 * tied to the Android version. It is not a case of "4.4 is affected and 5.1 is
 * never affected". It depends only on whether the CA store of a given device, at
 * the moment it left the factory, includes the root of the certificate chain that
 * xiaozhi.me/api.tenclass.net currently uses. A device that does not include it
 * cannot even get past the TLS handshake. It hits
 * java.security.cert.CertPathValidatorException: Trust anchor for certification
 * path not found (thrown by android.security.net.config.RootTrustManager).
 *
 * This class provides a TrustManager that always trusts any certificate chain and
 * a HostnameVerifier that always accepts any hostname. This keeps the XiaoZhi
 * connections (OTA activation code + WebSocket) working on such old devices with
 * an incomplete CA store. The cost: the TLS layer of these two connections no
 * longer defends against man-in-the-middle attacks. For a home robot that
 * connects to the official xiaozhi.me/Tenclass account, this trade-off exists to
 * keep the feature usable under the hard limit of API 22, where the CA store
 * cannot be patched. It should not be applied globally to other https
 * connections in this app that do perform verification.
 */
final class XiaozhiTrustAllSsl {
    private static final String TAG = "XiaozhiTrustAllSsl";

    private XiaozhiTrustAllSsl() {}

    /** A TrustManager that always trusts any certificate chain. Both
     *  checkClientTrusted/checkServerTrusted are intentionally left empty (that
     *  is, "check nothing, always pass"). Following the official
     *  javax.net.ssl.X509TrustManager documentation, in this "unrestricted
     *  issuer" use getAcceptedIssuers() returns an empty array. */
    private static final X509TrustManager TRUST_ALL_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Intentionally empty: do not verify, trust everything.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Intentionally empty: do not verify, trust everything.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /** Always accept any hostname. Because even the certificate chain is not
     *  verified, checking whether the hostname matches that untrusted
     *  certificate is meaningless. Keep the same trade-off as
     *  checkServerTrusted(). */
    static final HostnameVerifier TRUST_ALL_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
            return true;
        }
    };

    private static volatile SSLSocketFactory cachedFactory;
    private static volatile boolean warned;

    /** Return an SSLSocketFactory that already has TRUST_ALL_MANAGER installed.
     *  XiaozhiClient.connect() uses it to wrap the raw socket, in place of
     *  SSLSocketFactory.getDefault() (which follows the system CA store and hits
     *  CertPathValidatorException in this scenario). Lazy + cache:
     *  SSLContext.init() is not heavy, but there is no need to build a new one on
     *  every connection.
     *  Warning: this factory does not verify the certificate chain/hostname. It
     *  can be used only for the two XiaoZhi connections. A same-segment MITM can
     *  steal the token/voice. Do not use it on an untrusted network. */
    static SSLSocketFactory getTrustAllSocketFactory() {
        if (!warned) {
            warned = true;
            Log.w(TAG, "trust-all TLS in effect (does not verify certificate/hostname, XiaoZhi connections only; do not use on untrusted network)");
        }
        SSLSocketFactory factory = cachedFactory;
        if (factory != null) return factory;
        synchronized (XiaozhiTrustAllSsl.class) {
            if (cachedFactory == null) {
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[]{TRUST_ALL_MANAGER}, new SecureRandom());
                    cachedFactory = ctx.getSocketFactory();
                } catch (NoSuchAlgorithmException | KeyManagementException e) {
                    // This should not happen in theory (the "TLS" algorithm name
                    // and the SSLContext API exist on every Android version). If
                    // it does happen, fall back to the system default. At least
                    // the behavior reverts to "before the change". The app will
                    // not crash directly.
                    Log.e(TAG, "failed to create trust-all SSLContext, fall back to system default", e);
                    cachedFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                }
            }
            return cachedFactory;
        }
    }

    /** Install the trust-all SSLSocketFactory + HostnameVerifier on an
     *  HttpsURLConnection. XiaozhiOtaClient calls it when the connection it got
     *  from openConnection() is an HttpsURLConnection. If what is passed in is not
     *  an HttpsURLConnection (for example, if the URL was mistyped as http://), it
     *  does nothing and does not throw an exception. */
    static void applyTrustAll(java.net.HttpURLConnection conn) {
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
            httpsConn.setSSLSocketFactory(getTrustAllSocketFactory());
            httpsConn.setHostnameVerifier(TRUST_ALL_HOSTNAME_VERIFIER);
        }
    }
}


