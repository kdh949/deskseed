package dev.deskseed

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

@dev.deskseed.testsupport.category.IntegrationTest
class PersonalStagingNginxRoutingIntegrationTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `frontend routes health denies prometheus and enforces attachment body limits`() {
        Network.newNetwork().use { network ->
            val backend = GenericContainer(DockerImageName.parse("nginx:1.31-alpine"))
                .withNetwork(network)
                .withNetworkAliases("backend")
                .withCopyToContainer(
                    Transferable.of(Files.readAllBytes(repositoryFile("backend/src/test/resources/nginx/routing-backend.conf"))),
                    "/etc/nginx/nginx.conf",
                )
                .withExposedPorts(8080, 9090)
            val defaultFrontend = frontend(network, "frontend/nginx.conf")
            val observabilityFrontend = frontend(network, "frontend/nginx.personal-staging-observability.conf")

            try {
                backend.start()
                defaultFrontend.start()
                observabilityFrontend.start()

                val ordinaryHealth = get(defaultFrontend, "/actuator/health")
                assertThat(ordinaryHealth.statusCode()).isEqualTo(200)
                assertThat(ordinaryHealth.body()).contains("application-8080")

                val observabilityHealth = get(observabilityFrontend, "/actuator/health")
                assertThat(observabilityHealth.statusCode()).isEqualTo(200)
                assertThat(observabilityHealth.body()).contains("management-9090")

                assertThat(get(observabilityFrontend, "/actuator/prometheus").statusCode()).isEqualTo(404)

                for (frontend in listOf(defaultFrontend, observabilityFrontend)) {
                    assertThat(uploadHeaderStatus(frontend, 100)).isEqualTo(100)
                    assertThat(uploadHeaderStatus(frontend, 106)).isEqualTo(413)
                }
            } finally {
                observabilityFrontend.stop()
                defaultFrontend.stop()
                backend.stop()
            }
        }
    }

    private fun frontend(network: Network, config: String) = GenericContainer(DockerImageName.parse("nginx:1.31-alpine"))
        .withNetwork(network)
        .withCopyToContainer(
            Transferable.of(Files.readAllBytes(repositoryFile(config))),
            "/etc/nginx/conf.d/default.conf",
        )
        .withExposedPorts(80)

    private fun get(container: GenericContainer<*>, path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://${container.host}:${container.getMappedPort(80)}$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun uploadHeaderStatus(container: GenericContainer<*>, sizeMiB: Int): Int = Socket().use { socket ->
        socket.connect(InetSocketAddress(container.host, container.getMappedPort(80)), 5_000)
        socket.soTimeout = 5_000
        val headers = "POST /api/v1/requests HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Length: ${sizeMiB * 1024L * 1024L}\r\n" +
            "Expect: 100-continue\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
        val status = socket.getInputStream().bufferedReader().readLine()
            ?: error("Nginx closed the connection without an HTTP response")
        status.split(' ')[1].toInt()
    }

    private fun repositoryFile(relativePath: String): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(4) {
            val file = candidate.resolve(relativePath)
            if (Files.isRegularFile(file)) return file
            candidate = candidate.parent ?: return@repeat
        }
        error("Repository file is unavailable to routing test: $relativePath")
    }
}
