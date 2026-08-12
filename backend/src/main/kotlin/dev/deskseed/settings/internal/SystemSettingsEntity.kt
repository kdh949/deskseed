package dev.deskseed.settings.internal

import dev.deskseed.settings.CustomerAccessMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(name = "system_settings")
internal class SystemSettingsEntity(
    @Id
    val id: Int = SINGLETON_ID,

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_access_mode", nullable = false, length = 40)
    var customerAccessMode: CustomerAccessMode = CustomerAccessMode.ANONYMOUS_ALLOWED,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
) {
    companion object {
        const val SINGLETON_ID: Int = 1
    }
}
