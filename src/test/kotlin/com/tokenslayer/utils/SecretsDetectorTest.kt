package com.tokenslayer.utils

import com.tokenslayer.types.SecretsScanResult.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretsDetectorTest {
    // ── Content detection ─────────────────────────────────────────────────────

    @Test fun `detects AWS access key`() {
        val result = SecretsDetector.scan("config.java", "val key = \"AKIAIOSFODNN7EXAMPLE\"")
        assertTrue(result.hasSecrets)
        assertEquals(Severity.HIGH, result.severity)
        assertTrue(result.reasons.any { "AWS Access Key ID" in it })
    }

    @Test fun `detects GitHub token`() {
        val result = SecretsDetector.scan("auth.kt", "val token = \"ghp_abcdefghijklmnopqrstuvwxyz123456\"")
        assertTrue(result.hasSecrets)
        assertEquals(Severity.HIGH, result.severity)
        assertTrue(result.reasons.any { "GitHub token" in it })
    }

    @Test fun `detects Stripe secret key`() {
        val result = SecretsDetector.scan("payments.py", "STRIPE_KEY = 'sk_live_abcdefghij1234567890'")
        assertTrue(result.hasSecrets)
        assertEquals(Severity.HIGH, result.severity)
    }

    @Test fun `detects database connection string`() {
        val result = SecretsDetector.scan("db.ts", "const url = 'postgresql://admin:secretpassword123@prod-db.example.com:5432/mydb'")
        assertTrue(result.hasSecrets)
        assertEquals(Severity.HIGH, result.severity)
    }

    @Test fun `detects private key`() {
        val result = SecretsDetector.scan("cert.pem", "-----BEGIN RSA PRIVATE KEY-----\nMIIE...")
        assertTrue(result.hasSecrets)
    }

    @Test fun `detects Google API key`() {
        val result = SecretsDetector.scan("app.js", "const key = 'AIzaSyD-abcdefghijklmnopqrstuvwxyz12345'")
        assertTrue(result.hasSecrets)
    }

    @Test fun `detects Slack token`() {
        val result = SecretsDetector.scan("bot.py", "token = 'xoxb-1234567890-abcdefghijklmnopqrstuvwxyz'")
        assertTrue(result.hasSecrets)
    }

    @Test fun `clean code is not flagged`() {
        val result =
            SecretsDetector.scan(
                "Calculator.java",
                """
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                    public int multiply(int a, int b) { return a * b; }
                }
                """.trimIndent(),
            )
        assertFalse(result.hasSecrets)
        assertEquals(Severity.LOW, result.severity)
    }

    // ── Filename detection ────────────────────────────────────────────────────

    @Test fun `flags dotenv file by name`() {
        val result = SecretsDetector.scan(".env", "DB_HOST=localhost")
        assertTrue(result.hasSecrets)
        assertEquals(Severity.HIGH, result.severity)
    }

    @Test fun `flags PEM file by name`() {
        val result = SecretsDetector.scan("server.pem", "certificate data")
        assertTrue(result.hasSecrets)
    }

    @Test fun `flags credentials json by name`() {
        val result = SecretsDetector.scan("credentials.json", "{}")
        assertTrue(result.hasSecrets)
    }

    @Test fun `flags service account key by name`() {
        val result = SecretsDetector.scan("service-account-key.json", "{}")
        assertTrue(result.hasSecrets)
    }

    @Test fun `does not flag normal source files by name`() {
        val result = SecretsDetector.scan("Main.java", "public class Main {}")
        assertFalse(result.hasSecrets)
    }

    // ── Performance ───────────────────────────────────────────────────────────

    @Test fun `scans only first 5000 chars for performance`() {
        val cleanPrefix = "x".repeat(5001) // over the scan limit
        val secretSuffix = "AKIAIOSFODNN7EXAMPLE"
        val content = cleanPrefix + secretSuffix
        val result = SecretsDetector.scan("big.java", content)
        // Secret is beyond 5000 chars → should NOT be detected
        assertFalse(result.hasSecrets)
    }
}
