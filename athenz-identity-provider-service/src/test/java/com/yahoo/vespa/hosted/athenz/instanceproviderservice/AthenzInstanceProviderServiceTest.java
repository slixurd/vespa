// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.athenz.instanceproviderservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.Zone;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.AthenzInstanceProviderService.AthenzCertificateUpdater;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.config.AthenzProviderServiceConfig;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.impl.CertificateClient;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.impl.IdentityDocumentGenerator;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.impl.InstanceValidator;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.impl.KeyProvider;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.impl.Utils;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.impl.model.IdentityDocument;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.impl.model.InstanceConfirmation;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.impl.model.ProviderUniqueId;
import com.yahoo.vespa.hosted.athenz.instanceproviderservice.impl.model.SignedIdentityDocument;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyManagementException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author bjorncs
 */
public class AthenzInstanceProviderServiceTest {

    private static final Logger log = Logger.getLogger(AthenzInstanceProviderServiceTest.class.getName());
    private static final int PORT = 12345;
    private static final Zone ZONE = new Zone(SystemName.cd, Environment.dev, RegionName.from("us-north-1"));

    @Test
    public void provider_service_hosts_endpoint_secured_with_tls() throws Exception {
        String domain = "domain";
        String service = "service";

        AutoGeneratedKeyProvider keyProvider = new AutoGeneratedKeyProvider();
        PrivateKey privateKey = keyProvider.getPrivateKey(0);
        AthenzProviderServiceConfig config = getAthenzProviderConfig(domain, service, "vespa.dns.suffix");
        SslContextFactory sslContextFactory =  AthenzInstanceProviderService.createSslContextFactory();
        AthenzCertificateUpdater certificateUpdater = new AthenzCertificateUpdater(
                new SelfSignedCertificateClient(keyProvider.getKeyPair(), config),
                sslContextFactory,
                keyProvider,
                config);

        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        when(executor.awaitTermination(anyLong(), any())).thenReturn(true);

        InstanceValidator instanceValidator = mock(InstanceValidator.class);
        when(instanceValidator.isValidInstance(any())).thenReturn(true);

        IdentityDocumentGenerator identityDocumentGenerator = mock(IdentityDocumentGenerator.class);

        AthenzInstanceProviderService athenzInstanceProviderService = new AthenzInstanceProviderService(
                config, executor, ZONE, sslContextFactory, instanceValidator, identityDocumentGenerator, certificateUpdater);

        try (CloseableHttpClient client = createHttpClient(domain, service)) {
            assertFalse(getStatus(client));
            certificateUpdater.run();
            assertTrue(getStatus(client));
            assertInstanceConfirmationSucceeds(client, privateKey);
            certificateUpdater.run();
            assertTrue(getStatus(client));
            assertInstanceConfirmationSucceeds(client, privateKey);
        } finally {
            athenzInstanceProviderService.deconstruct();
        }
    }

    public static AthenzProviderServiceConfig getAthenzProviderConfig(String domain, String service, String dnsSuffix) {
        return new AthenzProviderServiceConfig(
                        new AthenzProviderServiceConfig.Builder()
                                .domain(domain)
                                .serviceName(service)
                                .port(PORT)
                                .keyPathPrefix("dummy-path")
                                .certDnsSuffix(dnsSuffix)
                                .ztsUrl("localhost/zts")
                                .athenzPrincipalHeaderName("Athenz-Principal-Auth")
                                .apiPath(""));

    }
    private static boolean getStatus(HttpClient client) {
        try {
            HttpResponse response = client.execute(new HttpGet("https://localhost:" + PORT + "/status.html"));
            return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
        } catch (Exception e) {
            log.log(LogLevel.INFO, "Status.html failed: " + e);
            return false;
        }
    }

    private static void assertInstanceConfirmationSucceeds(HttpClient client, PrivateKey privateKey) throws IOException {
        HttpPost httpPost = new HttpPost("https://localhost:" + PORT + "/instance");
        httpPost.setEntity(createInstanceConfirmation(privateKey));
        HttpResponse response = client.execute(httpPost);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
    }

    private static CloseableHttpClient createHttpClient(String domain, String service)
            throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException {
        SSLContext sslContext = new SSLContextBuilder()
                .loadTrustMaterial(null, (certificateChain, ignoredAuthType) ->
                        certificateChain[0].getSubjectX500Principal().getName().equals("CN=" + domain + "." + service))
                .build();

        return HttpClients.custom()
                .setSslcontext(sslContext)
                .setSSLHostnameVerifier(new NoopHostnameVerifier())
                .build();
    }

    private static HttpEntity createInstanceConfirmation(PrivateKey privateKey) {
        IdentityDocument identityDocument = new IdentityDocument(
                new ProviderUniqueId("tenant", "application", "environment", "region", "instance", "cluster-id", 0),
                "hostname",
                "instance-hostname",
                Instant.now());
        try {
            ObjectMapper mapper = Utils.getMapper();
            String encodedIdentityDocument =
                    Base64.getEncoder().encodeToString(mapper.writeValueAsString(identityDocument).getBytes());
            Signature sigGenerator = Signature.getInstance("SHA512withRSA");
            sigGenerator.initSign(privateKey);
            sigGenerator.update(encodedIdentityDocument.getBytes());

            InstanceConfirmation instanceConfirmation = new InstanceConfirmation(
                    "provider", "domain", "service",
                    new SignedIdentityDocument(encodedIdentityDocument,
                                               Base64.getEncoder().encodeToString(sigGenerator.sign()),
                                               0,
                                               identityDocument.providerUniqueId.asString(),
                                               "dnssuffix",
                                               "service",
                                               "localhost/zts",
                                               1));
            return new StringEntity(mapper.writeValueAsString(instanceConfirmation));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class AutoGeneratedKeyProvider implements KeyProvider {

        private final KeyPair keyPair;

        public AutoGeneratedKeyProvider() {
            try {
                KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
                rsa.initialize(2048);
                keyPair = rsa.genKeyPair();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public PrivateKey getPrivateKey(int version) {
            return keyPair.getPrivate();
        }

        @Override
        public PublicKey getPublicKey(int version) {
            return keyPair.getPublic();
        }

        public KeyPair getKeyPair() {
            return keyPair;
        }
    }

    private static class SelfSignedCertificateClient implements CertificateClient {

        private final KeyPair keyPair;
        private final AthenzProviderServiceConfig config;

        private SelfSignedCertificateClient(KeyPair keyPair, AthenzProviderServiceConfig config) {
            this.keyPair = keyPair;
            this.config = config;
        }

        @Override
        public X509Certificate updateCertificate(PrivateKey privateKey, TemporalAmount expiryTime) {
            try {
                ContentSigner contentSigner = new JcaContentSignerBuilder("SHA512WithRSA").build(keyPair.getPrivate());
                X500Name dnName = new X500Name("CN=" + config.domain() + "." + config.serviceName());
                Calendar calendar = Calendar.getInstance();
                calendar.add(Calendar.HOUR, 1);
                Date endDate = calendar.getTime();
                JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                        dnName, BigInteger.ONE, new Date(), endDate, dnName, keyPair.getPublic());
                certBuilder.addExtension(new ASN1ObjectIdentifier("2.5.29.19"), true, new BasicConstraints(true));

                return new JcaX509CertificateConverter()
                        .setProvider(new BouncyCastleProvider())
                        .getCertificate(certBuilder.build(contentSigner));
            } catch (CertificateException | CertIOException | OperatorCreationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
