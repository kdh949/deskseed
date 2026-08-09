package dev.deskseed.settings.internal

import org.springframework.data.jpa.repository.JpaRepository

internal interface SystemSettingsRepository : JpaRepository<SystemSettingsEntity, Int>
