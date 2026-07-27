package io.github.guillebot.streammux.contracts.kafka;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.kafka.common.security.auth.SslEngineFactory;

/** Kafka {@link SslEngineFactory} for clusters with non-public CAs (Rednet SASL_SSL). */
public final class TrustAllSslEngineFactory implements SslEngineFactory {

  private SSLContext sslContext;

  @Override
  public void configure(Map<String, ?> configs) {
    try {
      sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, new TrustManager[] {new TrustEverything()}, new SecureRandom());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to initialize trust-all SSL context", e);
    }
  }

  @Override
  public SSLEngine createClientSslEngine(String peerHost, int peerPort, String endpointIdentification) {
    SSLEngine engine = sslContext.createSSLEngine(peerHost, peerPort);
    engine.setUseClientMode(true);
    return engine;
  }

  @Override
  public SSLEngine createServerSslEngine(String peerHost, int peerPort) {
    SSLEngine engine = sslContext.createSSLEngine(peerHost, peerPort);
    engine.setUseClientMode(false);
    return engine;
  }

  @Override
  public boolean shouldBeRebuilt(Map<String, Object> nextConfigs) {
    return false;
  }

  @Override
  public Set<String> reconfigurableConfigs() {
    return Collections.emptySet();
  }

  @Override
  public KeyStore keystore() {
    return null;
  }

  @Override
  public KeyStore truststore() {
    return null;
  }

  @Override
  public void close() {}

  private static final class TrustEverything implements X509TrustManager {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
