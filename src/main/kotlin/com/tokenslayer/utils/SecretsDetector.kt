package com.tokenslayer.utils

import com.tokenslayer.types.SecretsScanResult
import com.tokenslayer.types.SecretsScanResult.Severity

/**
 * Detects credentials, secrets, and sensitive data in file content.
 * Files flagged by this detector are excluded from structural analysis
 * to prevent accidental leakage through LLM context.
 *
 * Direct port of VS Code's secretsDetector.ts — same regex patterns.
 */
object SecretsDetector {
    private data class ContentPattern(
        val regex: Regex,
        val description: String,
        val severity: Severity,
    )

    private data class FilenamePattern(
        val regex: Regex,
        val description: String,
    )

    // ── Content patterns ────────────────────────────────────────────────────
    private val CONTENT_PATTERNS: List<ContentPattern> =
        listOf(
            // API Keys & Tokens
            ContentPattern(
                Regex("""(?:api[_\-]?key|apikey)\s*[:=]\s*['"][A-Za-z0-9_\-]{16,}""", RegexOption.IGNORE_CASE),
                "API key",
                Severity.HIGH,
            ),
            ContentPattern(
                Regex("""(?:secret[_\-]?key|secretkey)\s*[:=]\s*['"][A-Za-z0-9_\-/+=]{16,}""", RegexOption.IGNORE_CASE),
                "Secret key",
                Severity.HIGH,
            ),
            ContentPattern(
                Regex("""(?:access[_\-]?token|accesstoken)\s*[:=]\s*['"][A-Za-z0-9_\-]{16,}""", RegexOption.IGNORE_CASE),
                "Access token",
                Severity.HIGH,
            ),
            ContentPattern(
                Regex("""(?:auth[_\-]?token|authtoken)\s*[:=]\s*['"][A-Za-z0-9_\-]{16,}""", RegexOption.IGNORE_CASE),
                "Auth token",
                Severity.HIGH,
            ),
            ContentPattern(Regex("""bearer\s+[A-Za-z0-9_.\-]{20,}""", RegexOption.IGNORE_CASE), "Bearer token", Severity.HIGH),
            // AWS
            ContentPattern(Regex("""AKIA[0-9A-Z]{16}"""), "AWS Access Key ID", Severity.HIGH),
            ContentPattern(
                Regex("""(?:aws[_\-]?secret|aws_secret_access_key)\s*[:=]\s*['"][A-Za-z0-9/+=]{30,}""", RegexOption.IGNORE_CASE),
                "AWS Secret Key",
                Severity.HIGH,
            ),
            // Private Keys & Certificates
            ContentPattern(Regex("""-----BEGIN\s+(?:RSA\s+)?PRIVATE\s+KEY-----""", RegexOption.IGNORE_CASE), "Private key", Severity.HIGH),
            ContentPattern(Regex("""-----BEGIN\s+CERTIFICATE-----""", RegexOption.IGNORE_CASE), "Certificate", Severity.MEDIUM),
            ContentPattern(Regex("""-----BEGIN\s+PGP\s+PRIVATE""", RegexOption.IGNORE_CASE), "PGP private key", Severity.HIGH),
            // Database Connection Strings
            ContentPattern(
                Regex(
                    """(?:mongodb|postgres(?:ql)?|mysql|redis|amqp|mssql|mariadb|couchdb|cassandra)://[^\s'"]{10,}""",
                    RegexOption.IGNORE_CASE,
                ),
                "Database connection string",
                Severity.HIGH,
            ),
            ContentPattern(Regex("""(?:password|passwd|pwd)\s*[:=]\s*['"][^'"]{6,}""", RegexOption.IGNORE_CASE), "Password", Severity.HIGH),
            ContentPattern(
                Regex("""(?:db[_\-]?password|database[_\-]?password)\s*[:=]\s*['"][^'"]{4,}""", RegexOption.IGNORE_CASE),
                "Database password",
                Severity.HIGH,
            ),
            // GitHub / GitLab / Bitbucket tokens
            ContentPattern(Regex("""gh[pousr]_[A-Za-z0-9_]{30,}""", RegexOption.IGNORE_CASE), "GitHub token", Severity.HIGH),
            ContentPattern(Regex("""glpat-[A-Za-z0-9\-]{20,}""", RegexOption.IGNORE_CASE), "GitLab token", Severity.HIGH),
            // Stripe
            ContentPattern(Regex("""sk_live_[A-Za-z0-9]{20,}""", RegexOption.IGNORE_CASE), "Stripe secret key", Severity.HIGH),
            ContentPattern(Regex("""rk_live_[A-Za-z0-9]{20,}""", RegexOption.IGNORE_CASE), "Stripe restricted key", Severity.HIGH),
            // Slack
            ContentPattern(Regex("""xox[baprs]-[A-Za-z0-9\-]{10,}""", RegexOption.IGNORE_CASE), "Slack token", Severity.HIGH),
            // Google
            ContentPattern(Regex("""AIza[0-9A-Za-z_\-]{35}""", RegexOption.IGNORE_CASE), "Google API key", Severity.HIGH),
            // JWT
            ContentPattern(
                Regex("""(?:jwt[_\-]?secret|jwt_secret_key)\s*[:=]\s*['"][^'"]{8,}""", RegexOption.IGNORE_CASE),
                "JWT secret",
                Severity.HIGH,
            ),
            // SSH
            ContentPattern(
                Regex("""(?:ssh[_\-]?pass|sshpass)\s*[:=]\s*['"][^'"]{4,}""", RegexOption.IGNORE_CASE),
                "SSH password",
                Severity.HIGH,
            ),
            // Generic environment secrets
            ContentPattern(
                Regex("""(?:SECRET|TOKEN|CREDENTIAL|AUTH)[A-Z_]*\s*=\s*['"]?[A-Za-z0-9_\-/+=]{20,}""", RegexOption.IGNORE_CASE),
                "Environment secret",
                Severity.MEDIUM,
            ),
        )

    // ── Filename patterns ────────────────────────────────────────────────────
    private val SENSITIVE_FILENAMES: List<FilenamePattern> =
        listOf(
            FilenamePattern(Regex("""\.env$"""), ".env file"),
            FilenamePattern(Regex("""\.env\.[a-z]+$"""), ".env variant file"),
            FilenamePattern(Regex("""\.env\.local$"""), ".env.local file"),
            FilenamePattern(Regex("""\.env\.production$"""), ".env.production file"),
            FilenamePattern(Regex("""\.pem$"""), "PEM certificate/key file"),
            FilenamePattern(Regex("""\.key$"""), "Key file"),
            FilenamePattern(Regex("""\.p12$"""), "PKCS12 certificate"),
            FilenamePattern(Regex("""\.pfx$"""), "PFX certificate"),
            FilenamePattern(Regex("""\.jks$"""), "Java keystore"),
            FilenamePattern(Regex("""\.keystore$"""), "Keystore file"),
            FilenamePattern(Regex("""id_rsa$"""), "SSH private key"),
            FilenamePattern(Regex("""id_ed25519$"""), "SSH private key"),
            FilenamePattern(Regex("""credentials\.json$""", RegexOption.IGNORE_CASE), "Credentials file"),
            FilenamePattern(Regex("""secrets\.json$""", RegexOption.IGNORE_CASE), "Secrets file"),
            FilenamePattern(Regex("""secrets\.ya?ml$""", RegexOption.IGNORE_CASE), "Secrets YAML file"),
            FilenamePattern(Regex("""\.htpasswd$"""), "htpasswd file"),
            FilenamePattern(Regex("""\.netrc$"""), "netrc file"),
            FilenamePattern(Regex("""service[_\-]?account.*\.json$""", RegexOption.IGNORE_CASE), "Service account key"),
        )

    /**
     * Scan file content and filename for secrets.
     * Scans only the first 5000 chars for performance (same as VS Code version).
     */
    fun scan(
        filePath: String,
        content: String,
    ): SecretsScanResult {
        val reasons = mutableListOf<String>()
        var highestSeverity = Severity.LOW

        fun raiseSeverity(s: Severity) {
            if (s == Severity.HIGH) {
                highestSeverity = Severity.HIGH
            } else if (s == Severity.MEDIUM && highestSeverity != Severity.HIGH) {
                highestSeverity = Severity.MEDIUM
            }
        }

        // Check filename
        val fileName = filePath.split("/", "\\").last()
        for (fp in SENSITIVE_FILENAMES) {
            if (fp.regex.containsMatchIn(fileName)) {
                reasons.add("Filename match: ${fp.description}")
                raiseSeverity(Severity.HIGH)
            }
        }

        // Check content (first 5000 chars for performance)
        val scanContent = content.take(5000)
        for (cp in CONTENT_PATTERNS) {
            if (cp.regex.containsMatchIn(scanContent)) {
                reasons.add("Content match: ${cp.description}")
                raiseSeverity(cp.severity)
            }
        }

        return SecretsScanResult(
            hasSecrets = reasons.isNotEmpty(),
            reasons = reasons,
            severity = if (reasons.isNotEmpty()) highestSeverity else Severity.LOW,
        )
    }
}
