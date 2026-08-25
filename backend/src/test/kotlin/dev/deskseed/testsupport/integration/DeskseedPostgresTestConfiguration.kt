package dev.deskseed.testsupport.integration

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class DeskseedPostgresTestConfiguration {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer(
        DockerImageName.parse("postgres:17-alpine"),
    )

    @Bean
    fun staffTicketTestDatabaseCleaner(jdbcTemplate: JdbcTemplate): StaffTicketTestDatabaseCleaner =
        StaffTicketTestDatabaseCleaner(jdbcTemplate)
}
