package com.evofactory.homeboard

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateNamesTest {
    // Self-signed, with subjectAltName DNS:frigate.home.example.com, DNS:*.lan.example.com, IP:10.77.77.5
    private val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIBjzCCATagAwIBAgIUKmp3l/f4VYdrpfcwkRaALdgGwOEwCgYIKoZIzj0EAwIw
        DzENMAsGA1UEAwwEdGVzdDAeFw0yNjExMTExMDM4MzhaFw0zNjExMDgxMDM4Mzha
        MA8xDTALBgNVBAMMBHRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQhxOMF
        GPlVcKo2OEwvu2/B9Uj54jYb+806cP6b8O3eVmunkMQiOT7xd95xEK0hSyfRpXOi
        dDptiog6gloHVo79o3AwbjAdBgNVHQ4EFgQUzqAg23BGjs3AuQJOu0S0kFJwNFMw
        DwYDVR0TAQH/BAUwAwEB/zA8BgNVHREENTAzghhmcmlnYXRlLmhvbWUuZXhhbXBs
        ZS5jb22CESoubGFuLmV4YW1wbGUuY29thwQKTU0FMAoGCCqGSM49BAMCA0cAMEQC
        IDE9/HbzHVAp5PmiEgQ0Mi+Q3jabux6vFYCRKb4PJS2WAiBORwqeybpNtWmKT7hj
        TuSTGjq2mEF5kqDZALr9QgeBMw==
        -----END CERTIFICATE-----
        """.trimIndent().byteInputStream(),
    ) as X509Certificate

    @Test
    fun dnsNames() {
        assertTrue(certificateNames(certificate, "frigate.home.example.com"))
        assertTrue(certificateNames(certificate, "FRIGATE.home.example.com."))
        assertFalse(certificateNames(certificate, "home.example.com"))
        assertFalse(certificateNames(certificate, "evil.frigate.home.example.com"))
        assertFalse(certificateNames(certificate, "frigate.home.example.com.evil.net"))
    }

    @Test
    fun wildcardCoversOneLabel() {
        assertTrue(certificateNames(certificate, "frigate.lan.example.com"))
        assertFalse(certificateNames(certificate, "lan.example.com"))
        assertFalse(certificateNames(certificate, "a.b.lan.example.com"))
        assertFalse(certificateNames(certificate, "xlan.example.com"))
    }

    @Test
    fun addresses() {
        assertTrue(certificateNames(certificate, "10.77.77.5"))
        assertFalse(certificateNames(certificate, "10.77.77.6"))
        // An address is never matched by a DNS name, nor a name by an address
        assertFalse(certificateNames(certificate, "127.0.0.1"))
    }
}
