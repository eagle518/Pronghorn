package com.ociweb.pronghorn.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.InputStream;
import java.security.KeyStore;

//import io.netty.handler.ssl.SslContext;

public class SSLEngineFactory {

	private static final Logger log = LoggerFactory.getLogger(SSLEngineFactory.class);
    private TLSService privateService;
	private final KeyManagerFactory keyManagerFactory;
	private final TrustManagerFactory trustManagerFactory;

    public SSLEngineFactory(TLSCertificates policy) {
        // Server Identity
        InputStream keyInputStream = policy.keyInputStream();
        // All the internet sites client trusts
        InputStream trustInputStream = policy.trustInputStream();

        String keyPassword = policy.keyPassword();
        String keyStorePassword = policy.keyStorePassword();

        if (keyInputStream != null) {
            try {
                keyManagerFactory = createKeyManagers(keyInputStream, keyStorePassword, keyPassword);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create key manager.", e);
            }
        }
        else {
            keyManagerFactory = null;
        }

        if (trustInputStream != null) {
            try {
                trustManagerFactory = createTrustManagers(trustInputStream, keyStorePassword);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create trust manager.", e);
            }
        }
        else {
            trustManagerFactory = null;
        }
    }

    TLSService getService() {
        if (privateService==null) {
            privateService = new TLSService(keyManagerFactory, trustManagerFactory, true);
        }
        return privateService;
    }

    /**
     * Creates the key managers required to initiate the {@link SSLContext}, using a JKS keystore as an input.
     *
     * @param keystorePassword - the keystore's password.
     * @param keyPassword - the key's passsword.
     * @return {@link KeyManager} array that will be used to initiate the {@link SSLContext}.
     * @throws Exception
     */
    private static KeyManagerFactory createKeyManagers(InputStream keyStoreIS, String keystorePassword, String keyPassword) throws Exception  {

    	KeyStore keyStore = KeyStore.getInstance("JKS");
        try {
            keyStore.load(keyStoreIS, keystorePassword.toCharArray());
        } finally {
            if (keyStoreIS != null) {
                keyStoreIS.close();
            }
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPassword.toCharArray());
        return kmf;
    }

    /**
     * Creates the trust managers required to initiate the {@link SSLContext}, using a JKS keystore as an input.
     *
     * @param keystorePassword - the keystore's password.
     * @return {@link TrustManager} array, that will be used to initiate the {@link SSLContext}.
     * @throws Exception
     */
    private static TrustManagerFactory createTrustManagers(InputStream trustStoreIS, String keystorePassword) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try {
            trustStore.load(trustStoreIS, keystorePassword.toCharArray());            
        } finally {
            if (trustStoreIS != null) {
                trustStoreIS.close();
            }
        }
        TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustFactory.init(trustStore);
        return trustFactory;
    }

    SSLEngine createSSLEngine(String host, int port) {
    	return getService().createSSLEngineClient(host, port);
    }

    SSLEngine createSSLEngine() {
    	return getService().createSSLEngineServer();
    }

}
