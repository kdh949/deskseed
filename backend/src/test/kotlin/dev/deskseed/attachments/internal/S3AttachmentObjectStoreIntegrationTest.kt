package dev.deskseed.attachments.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
@dev.deskseed.testsupport.category.IntegrationTest
class S3AttachmentObjectStoreIntegrationTest {
    @Test
    fun `VersityGW adapter streams multipart bytes and supports private read delete lifecycle`() {
        val properties = S3AttachmentStorageProperties(
            endpoint = URI.create("http://versitygw:7070"),
            region = "us-east-1",
            bucket = "deskseed-attachments-test",
            accessKey = ACCESS_KEY,
            secretKey = SECRET_KEY,
            pathStyleAccessEnabled = true,
            createBucketIfMissing = true,
            plaintextInternalNetworkAcknowledged = true,
        )
        val client = S3Client.builder()
            .endpointOverride(URI.create("http://${versity.host}:${versity.getMappedPort(7070)}"))
            .region(Region.of(properties.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)),
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()
        val store = S3AttachmentObjectStore(client, properties)
        val key = "attachments/quarantine/${UUID.randomUUID()}"
        val payload = ByteArray(8 * 1024 * 1024 + 257) { index -> (index % 251).toByte() }

        try {
            assertThat(store.putQuarantine(key, ByteArrayInputStream(payload))).isEqualTo(payload.size.toLong())
            assertThat(store.openPrivate(key).use { it.readAllBytes() }).isEqualTo(payload)
            store.delete(key)
            assertThatThrownBy { store.openPrivate(key) }
                .isInstanceOf(dev.deskseed.attachments.AttachmentUnavailableException::class.java)
        } finally {
            client.close()
        }
    }

    @Test
    fun `plaintext S3 endpoint is restricted to acknowledged Compose Versity service`() {
        assertThatThrownBy {
            S3AttachmentStorageProperties(
                endpoint = URI.create("http://external-storage.example.test:7070"),
                accessKey = ACCESS_KEY,
                secretKey = SECRET_KEY,
                plaintextInternalNetworkAcknowledged = true,
            ).validate()
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            S3AttachmentStorageProperties(
                endpoint = URI.create("http://versitygw:7070"),
                accessKey = ACCESS_KEY,
                secretKey = SECRET_KEY,
                plaintextInternalNetworkAcknowledged = false,
            ).validate()
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    companion object {
        private const val ACCESS_KEY = "deskseed-test-access"
        private const val SECRET_KEY = "deskseed-test-secret-key"

        @Container
        @JvmStatic
        val versity = GenericContainer(DockerImageName.parse("ghcr.io/versity/versitygw:v1.4.1"))
            .withEnv("ROOT_ACCESS_KEY", ACCESS_KEY)
            .withEnv("ROOT_SECRET_KEY", SECRET_KEY)
            .withEnv("VGW_PORT", ":7070")
            .withEnv("VGW_HEALTH", "/health")
            .withEnv("VGW_BACKEND", "posix")
            .withEnv("VGW_BACKEND_ARGS", "/tmp")
            .withExposedPorts(7070)
            .waitingFor(Wait.forHttp("/health").forPort(7070))
    }
}
