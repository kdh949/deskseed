package dev.deskseed

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class DeskseedApplication

fun main(args: Array<String>) {
    runApplication<DeskseedApplication>(*args)
}
