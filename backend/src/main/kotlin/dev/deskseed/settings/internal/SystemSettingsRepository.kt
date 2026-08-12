package dev.deskseed.settings.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

internal interface SystemSettingsRepository : JpaRepository<SystemSettingsEntity, Int> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select settings from SystemSettingsEntity settings where settings.id = 1")
    fun lockSingleton(): SystemSettingsEntity?
}
