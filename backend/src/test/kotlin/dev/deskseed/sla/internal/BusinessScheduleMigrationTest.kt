package dev.deskseed.sla.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException

@Testcontainers
@dev.deskseed.testsupport.category.MigrationTest
class BusinessScheduleMigrationTest {
    @Test
    fun `migration seeds Seoul weekday schedule and protects immutable history`() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select v.name, v.timezone, s.current_version, s.active_version,
                           count(i.weekday) filter (where w.enabled) as enabled_interval_count
                    from business_schedules s
                    join business_schedule_versions v
                      on v.schedule_id = s.id and v.version = s.current_version
                    join business_schedule_weekdays w
                      on w.schedule_id = v.schedule_id and w.schedule_version = v.version
                    left join business_schedule_weekday_intervals i
                      on i.schedule_id = w.schedule_id and i.schedule_version = w.schedule_version
                     and i.weekday = w.weekday
                    where s.id = '51000000-0000-0000-0000-000000000001'
                    group by v.name, v.timezone, s.current_version, s.active_version
                    """.trimIndent(),
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("name")).isEqualTo("Default Support Hours")
                    assertThat(result.getString("timezone")).isEqualTo("Asia/Seoul")
                    assertThat(result.getInt("current_version")).isEqualTo(1)
                    assertThat(result.getInt("active_version")).isEqualTo(1)
                    assertThat(result.getInt("enabled_interval_count")).isEqualTo(5)
                }

                assertThat(
                    statement.executeQuery(
                        """
                        select count(*)
                        from business_schedule_weekdays
                        where schedule_id = '51000000-0000-0000-0000-000000000001'
                          and schedule_version = 1
                          and enabled = false
                          and weekday in ('SATURDAY', 'SUNDAY')
                        """.trimIndent(),
                    ).use { result -> result.next(); result.getInt(1) },
                ).isEqualTo(2)

                assertImmutable {
                    statement.executeUpdate(
                        """
                        update business_schedule_versions
                           set timezone = 'UTC'
                         where schedule_id = '51000000-0000-0000-0000-000000000001'
                           and version = 1
                        """.trimIndent(),
                    )
                }
                assertImmutable {
                    statement.executeUpdate(
                        """
                        delete from business_schedule_weekday_intervals
                         where schedule_id = '51000000-0000-0000-0000-000000000001'
                           and schedule_version = 1
                        """.trimIndent(),
                    )
                }
                assertImmutable {
                    statement.executeUpdate(
                        """
                        delete from business_schedule_activations
                         where schedule_id = '51000000-0000-0000-0000-000000000001'
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    private fun assertImmutable(block: () -> Unit) {
        assertThatThrownBy(block).isInstanceOf(SQLException::class.java)
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
