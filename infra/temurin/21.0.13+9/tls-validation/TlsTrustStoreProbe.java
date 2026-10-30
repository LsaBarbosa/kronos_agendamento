import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.cert.X509Certificate;
import java.security.KeyStore;
import java.security.Security;
import java.util.Enumeration;
import java.util.List;

public final class TlsTrustStoreProbe {
    private static final List<String> COMPROMISED_CHAIN_HOSTS = List.of(
            "dv-g3b-emudhra.we-have-a-problem.com",
            "dv-g3c-emudhra.we-have-a-problem.com",
            "ov-g3b-emudhra.we-have-a-problem.com",
            "ov-g3c-emudhra.we-have-a-problem.com",
            "ev-g3b-emudhra.we-have-a-problem.com",
            "ev-g3c-emudhra.we-have-a-problem.com",
            "dv-g3b-aaa-emudhra.we-have-a-problem.com",
            "dv-g3c-aaa-emudhra.we-have-a-problem.com",
            "ov-g3b-aaa-emudhra.we-have-a-problem.com",
            "ov-g3c-aaa-emudhra.we-have-a-problem.com",
            "ev-g3b-aaa-emudhra.we-have-a-problem.com",
            "ev-g3c-aaa-emudhra.we-have-a-problem.com",
            "dv-g3b-noct-emudhra.we-have-a-problem.com",
            "dv-g3b-aaa-noct-emudhra.we-have-a-problem.com"
    );

    public static void main(String[] args) throws Exception {
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        String javaHome = System.getProperty("java.home");
        String trustStoreOverride = System.getProperty("javax.net.ssl.trustStore");
        if (trustStoreOverride != null) {
            throw new IllegalStateException("Unexpected javax.net.ssl.trustStore override: " + trustStoreOverride);
        }

        Path javaTrustStore = Path.of(javaHome, "lib", "security", "cacerts");
        Path alternateJavaTrustStore = Path.of(javaHome, "lib", "security", "jssecacerts");
        if (!Files.isReadable(javaTrustStore)) {
            throw new IllegalStateException("Java default trust store is not readable: " + javaTrustStore);
        }
        if (Files.exists(alternateJavaTrustStore)) {
            throw new IllegalStateException("Unexpected alternate JSSE trust store: " + alternateJavaTrustStore);
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init((KeyStore) null);
        KeyStore defaultStore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (var input = Files.newInputStream(javaTrustStore, StandardOpenOption.READ)) {
            defaultStore.load(input, "changeit".toCharArray());
        }
        boolean g1Present = false;
        boolean compromisedG3Present = false;
        boolean compromisedAaaPresent = false;
        Enumeration<String> aliases = defaultStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            var certificate = defaultStore.getCertificate(alias);
            if (certificate instanceof X509Certificate x509) {
                String subject = x509.getSubjectX500Principal().getName();
                if (subject.contains("emSign Root CA - G1")) {
                    g1Present = true;
                    System.out.println("trustAliasPresent=" + alias + " (emSign Root CA - G1)");
                }
                if (subject.contains("emSign ECC Root CA - G3")) {
                    compromisedG3Present = true;
                    System.out.println("trustAliasPresent=" + alias + " (unexpected compromised root)");
                }
                if (subject.contains("AAA Certificate Services")) {
                    compromisedAaaPresent = true;
                    System.out.println("trustAliasPresent=" + alias + " (unexpected compromised root)");
                }
            }
        }
        if (!g1Present || compromisedG3Present || compromisedAaaPresent) {
            throw new IllegalStateException("Unexpected eMudhra root presence in JRE cacerts");
        }

        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("java.runtime.version=" + System.getProperty("java.runtime.version"));
        System.out.println("java.vendor=" + System.getProperty("java.vendor"));
        System.out.println("user.name=" + System.getProperty("user.name"));
        System.out.println("java.home=" + javaHome);
        System.out.println("javax.net.ssl.trustStore=<JRE default>");
        System.out.println("trustStorePath=" + javaTrustStore);
        System.out.println("trustManagers=" + trustManagers.getTrustManagers().length);
        System.out.println("trustAliasAbsent=emSign ECC Root CA - G3");
        System.out.println("trustAliasAbsent=AAA Certificate Services");

        for (String host : COMPROMISED_CHAIN_HOSTS) {
            expectUntrustedChain(host);
        }
        expectOrdinaryHttps("probe-emudhra.we-have-a-problem.com");
        expectOrdinaryHttps("adoptium.net");
    }

    private static void expectUntrustedChain(String host) throws Exception {
        HttpsURLConnection connection = open("https://" + host + "/");
        try {
            int status = connection.getResponseCode();
            throw new AssertionError("Compromised test chain was accepted: host=" + host + ", HTTP=" + status);
        } catch (SSLHandshakeException rejected) {
            if (!hasCertPathRejection(rejected)) {
                throw new AssertionError("Handshake failed without a certificate-path rejection for " + host, rejected);
            }
            System.out.println("REJECTED compromised TLS chain: " + host + " (PKIX path validation failed)");
        } finally {
            connection.disconnect();
        }
    }

    private static void expectOrdinaryHttps(String host) throws Exception {
        HttpsURLConnection connection = open("https://" + host + "/");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 400) {
                throw new AssertionError("Ordinary HTTPS returned unexpected HTTP status: " + status);
            }
            System.out.println("ACCEPTED ordinary HTTPS: " + host + " (HTTP " + status + ")");
        } finally {
            connection.disconnect();
        }
    }

    private static HttpsURLConnection open(String address) throws Exception {
        URI uri = URI.create(address);
        UnknownHostException lastDnsFailure = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                InetAddress.getAllByName(uri.getHost());
                lastDnsFailure = null;
                if (attempt > 1) {
                    System.out.println("DNS transient recovered: " + uri.getHost() + " after attempt " + attempt);
                }
                break;
            } catch (UnknownHostException unavailable) {
                lastDnsFailure = unavailable;
                if (attempt < 4) {
                    Thread.sleep(250L * attempt);
                }
            }
        }
        if (lastDnsFailure != null) {
            throw lastDnsFailure;
        }
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Temurin-Noble-JRE-validation/1.0");
        return connection;
    }

    private static boolean hasCertPathRejection(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (current.getClass().getName().contains("CertPath")
                    || current.getClass().getName().contains("SunCertPathBuilder")
                    || (message != null && (message.contains("unable to find valid certification path")
                    || message.contains("Path does not chain with any of the trust anchors")))) {
                return true;
            }
        }
        return false;
    }
}
