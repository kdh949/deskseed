package dev.deskseed

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DeskseedApplication

fun main(args: Array<String>) {
    runApplication<DeskseedApplication>(*args)
}
