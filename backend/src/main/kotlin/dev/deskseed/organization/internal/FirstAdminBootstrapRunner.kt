package dev.deskseed.organization.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
internal class FirstAdminBootstrapRunner(
    private val bootstrapService: StaffBootstrapService,
    @Value("\${deskseed.staff-auth.bootstrap.enabled:true}")
    private val enabled: Boolean,
    @Value("\${deskseed.staff-auth.bootstrap.email:}")
    private val email: String,
    @Value("\${deskseed.staff-auth.bootstrap.display-name:}")
    private val displayName: String,
    @Value("\${deskseed.staff-auth.bootstrap.password-file:}")
    private val passwordFile: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (!enabled) return
        if (email.isBlank() && passwordFile.isBlank()) return
        require(email.isNotBlank() && passwordFile.isNotBlank()) {
            "First-admin bootstrap requires both email and password-file configuration"
        }
        require(email.length <= 254 && EMAIL_PATTERN.matches(email.trim())) {
            "First-admin bootstrap email is invalid"
        }
        val path = Path.of(passwordFile)
        require(Files.isRegularFile(path)) { "First-admin bootstrap password file is not a regular file" }
        val password = Files.readString(path).trimEnd('\r', '\n')
        require(password.length in 12..128) {
            "First-admin bootstrap password must contain 12 to 128 characters"
        }
        val safeDisplayName = displayName.trim().ifBlank { "Deskseed Admin" }
        require(safeDisplayName.length <= 100) { "First-admin display name is too long" }
        bootstrapService.bootstrap(email.trim(), safeDisplayName, password)
    }

    companion object {
        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+$")
    }
}
