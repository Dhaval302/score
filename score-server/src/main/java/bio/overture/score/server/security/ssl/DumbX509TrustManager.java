package bio.overture.score.server.security.ssl;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

public class DumbX509TrustManager implements X509TrustManager {
  public DumbX509TrustManager() {}

  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {}

  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {}

  public X509Certificate[] getAcceptedIssuers() {
    return null;
  }
}
