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

    // ── Unquoted values (YAML / .env style) ───────────────────────────────
    // YAML and .env conventionally omit quotes around scalar values — `password: hunter2`, not
    // `password: "hunter2"`. A values.yaml or rendered k8s Secret manifest is written exactly
    // this way, so a detector that only matched quoted values missed it entirely.

    @Test fun `detects an unquoted password in YAML-style content`() {
        val result = SecretsDetector.scan("values.yaml", "password: hunter2yolo")
        assertTrue(result.hasSecrets)
        assertTrue(result.reasons.any { "Password" in it })
    }

    @Test fun `detects an unquoted api key in YAML-style content`() {
        val result = SecretsDetector.scan("values.yaml", "api_key: sk_test_abcdefgh12345678")
        assertTrue(result.hasSecrets)
        assertTrue(result.reasons.any { "API key" in it })
    }

    @Test fun `still detects a quoted password (no regression)`() {
        val result = SecretsDetector.scan("config.py", "PASSWORD = 'hunter2yolo'")
        assertTrue(result.hasSecrets)
    }

    @Test fun `generic environment secret pattern accepts a colon separator`() {
        // Previously only '=' (.env style) was accepted; YAML writes ':' instead.
        val result = SecretsDetector.scan("values.yaml", "MY_APP_TOKEN: abcdefghijklmnopqrstuvwx")
        assertTrue(result.hasSecrets)
    }

    @Test fun `ordinary YAML config with no secrets is not flagged`() {
        val result =
            SecretsDetector.scan(
                "values.yaml",
                """
                replicas: 3
                image: nginx:1.25
                port: 8080
                environment: production
                """.trimIndent(),
            )
        assertFalse(result.hasSecrets)
    }

    // ── Scan limit ────────────────────────────────────────────────────────

    @Test fun `detects a secret well past the old 5000-char limit`() {
        // A multi-thousand-line manifest can put a real secret far beyond where the old
        // 5,000-char cap stopped looking; the new limit must actually reach it.
        val cleanPrefix = "x".repeat(50_000)
        val content = "$cleanPrefix\nAKIAIOSFODNN7EXAMPLE"
        val result = SecretsDetector.scan("big-manifest.yaml", content)
        assertTrue(result.hasSecrets)
    }

    @Test fun `still stops scanning at the (raised) limit`() {
        val cleanPrefix = "x".repeat(200_001) // just past the new limit
        val content = cleanPrefix + "AKIAIOSFODNN7EXAMPLE"
        val result = SecretsDetector.scan("huge.yaml", content)
        assertFalse(result.hasSecrets)
    }
}
