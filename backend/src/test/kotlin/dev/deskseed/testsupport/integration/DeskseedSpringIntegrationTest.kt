package dev.deskseed.testsupport.integration

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.annotation.AliasFor
import org.springframework.context.annotation.Import

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@SpringBootTest
@Import(DeskseedPostgresTestConfiguration::class)
annotation class DeskseedSpringIntegrationTest(
    @get:AliasFor(annotation = SpringBootTest::class, attribute = "properties")
    val properties: Array<String> = ["deskseed.staff-auth.bootstrap.enabled=false"],

    @get:AliasFor(annotation = SpringBootTest::class, attribute = "webEnvironment")
    val webEnvironment: SpringBootTest.WebEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
