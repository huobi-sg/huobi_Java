package com.huobi.utils;

import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.huobi.exception.SDKException;


public class ConnectionFactory {

  private static Boolean LATENCY_DEBUG_SWATCH = Boolean.FALSE;

  private static LinkedBlockingQueue<NetworkLatency> LATENCY_DEBUG_QUEUE = new LinkedBlockingQueue<>();

  private static ConnectionPool connectionPool =
      new ConnectionPool(20, 300, TimeUnit.SECONDS);

  
  
  private static class TrustAllCerts implements X509TrustManager {
      public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
      public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
      public X509Certificate[] getAcceptedIssuers() {return new X509Certificate[0];}
  }
  private static class TrustAllHostnameVerifier implements HostnameVerifier {
      public boolean verify(String hostname, SSLSession session) {return true;}
  }
  
  private static SSLSocketFactory createSSLSocketFactory() {
      SSLSocketFactory ssfFactory = null;
      try {
          SSLContext sc = SSLContext.getInstance("TLSv1.3");
          sc.init(null,  new TrustManager[] { new TrustAllCerts() }, new SecureRandom());
          ssfFactory = sc.getSocketFactory();
      } catch (Exception e) {e.printStackTrace();}
      return ssfFactory;
  }
  
  private static final OkHttpClient client = new OkHttpClient.Builder()
      .sslSocketFactory(createSSLSocketFactory(), new TrustAllCerts())
      .hostnameVerifier(new TrustAllHostnameVerifier())
      .followSslRedirects(false)
      .followRedirects(false)
      .connectTimeout(5000, TimeUnit.MILLISECONDS)
      .readTimeout(5000, TimeUnit.MILLISECONDS)
      .writeTimeout(5000, TimeUnit.MILLISECONDS)
      .connectionPool(connectionPool)
      .addNetworkInterceptor(new Interceptor() {
        @NotNull
        @Override
        public Response intercept(@NotNull Chain chain) throws IOException {
          Request request = chain.request();

          Long startNano = System.nanoTime();

          Response response = chain.proceed(request);

          Long endNano = System.nanoTime();

          if (LATENCY_DEBUG_SWATCH) {
            LATENCY_DEBUG_QUEUE.add(new NetworkLatency(request.url().url().getPath(), startNano, endNano));
          }

          return response;
        }
      })
      
      .build();

  private static final Logger log = LoggerFactory.getLogger(ConnectionFactory.class);

  public static String execute(Request request) {

    Response response = null;
    String str = null;
    try {
      log.debug("[Request URL]{}", request.url());
      response = client.newCall(request).execute();
      if (response.code() != 200) {
        throw new SDKException(SDKException.EXEC_ERROR, "[Execute] Response Status Error : " + response.code() + " message:" + response.message());
      }
      if (response != null && response.body() != null) {
        str = response.body().string();
        response.close();
      } else {
        throw new SDKException(SDKException.ENV_ERROR, "[Execute] Cannot get the response from server");
      }
      log.debug("[Response]{}", str);
      return str;
    } catch (IOException e) {
      e.printStackTrace();
      throw new SDKException(SDKException.RUNTIME_ERROR, "[Execute] Cannot get the response from server");
    }

  }

  public static WebSocket createWebSocket(Request request, WebSocketListener listener) {
    return client.newWebSocket(request, listener);
  }

  public static void setLatencyDebug() {
    LATENCY_DEBUG_SWATCH = Boolean.TRUE;
  }

  public static LinkedBlockingQueue<NetworkLatency> getLatencyDebugQueue() {
    return LATENCY_DEBUG_QUEUE;
  }

  public static void clearLatencyDebugQueue() {
    LATENCY_DEBUG_QUEUE.clear();
  }

  @Data
  @Builder
  @AllArgsConstructor
  @NoArgsConstructor
  public static class NetworkLatency {

    private String path;

    private Long startNanoTime;

    private Long endNanoTime;
  }
}
