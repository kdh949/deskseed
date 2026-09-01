package dev.deskseed.attachments.internal

import dev.deskseed.attachments.AttachmentObjectStore
import dev.deskseed.attachments.AttachmentTooLargeException
import dev.deskseed.attachments.AttachmentUnavailableException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Duration

@ConfigurationProperties("deskseed.attachments.s3")
internal data class S3AttachmentStorageProperties(
    var endpoint: URI = URI.create("https://invalid.invalid"),
    var region: String = "us-east-1",
    var bucket: String = "deskseed-attachments",
    var accessKey: String = "",
    var secretKey: String = "",
    var pathStyleAccessEnabled: Boolean = true,
    var createBucketIfMissing: Boolean = false,
    var plaintextInternalNetworkAcknowledged: Boolean = false,
) {
    fun validate() {
        require(endpoint.scheme in setOf("http", "https") && endpoint.host != null) {
            "attachment S3 endpoint must be an absolute HTTP(S) URI"
        }
        require(endpoint.userInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null) {
            "attachment S3 endpoint must not contain credentials, query, or fragment"
        }
        require(endpoint.path.isNullOrEmpty() || endpoint.path == "/") {
            "attachment S3 endpoint must not contain a path"
        }
        if (endpoint.scheme == "http") {
            require(
                endpoint.host == "versitygw" && endpoint.port == 7070 && plaintextInternalNetworkAcknowledged,
            ) {
                "plaintext attachment S3 is restricted to acknowledged Compose-internal VersityGW"
            }
        }
        require(region.matches(Regex("^[a-z0-9-]{3,32}$"))) { "attachment S3 region is invalid" }
        require(bucket.matches(Regex("^(?![0-9.]+$)[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$"))) {
            "attachment S3 bucket is invalid"
        }
        require(bucket != "health") { "attachment S3 bucket conflicts with the VersityGW health endpoint" }
        require(accessKey.length in 3..128) { "attachment S3 access key is invalid" }
        require(secretKey.length in 16..256) { "attachment S3 secret key is invalid" }
        require(pathStyleAccessEnabled) { "VersityGW attachment storage requires path-style S3 addressing" }
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("production")
@EnableConfigurationProperties(S3AttachmentStorageProperties::class)
internal class S3AttachmentStorageConfiguration {
    @Bean(destroyMethod = "close")
    fun s3Client(properties: S3AttachmentStorageProperties): S3Client {
        properties.validate()
        return S3Client.builder()
            .endpointOverride(properties.endpoint)
            .region(Region.of(properties.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey, properties.secretKey)),
            )
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(properties.pathStyleAccessEnabled)
                    .build(),
            )
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(3))
                    .socketTimeout(Duration.ofSeconds(30)),
            )
            .build()
    }

    @Bean
    fun attachmentObjectStore(
        client: S3Client,
        properties: S3AttachmentStorageProperties,
    ): AttachmentObjectStore = S3AttachmentObjectStore(client, properties)
}

internal class S3AttachmentObjectStore(
    private val client: S3Client,
    private val properties: S3AttachmentStorageProperties,
) : AttachmentObjectStore {
    init {
        properties.validate()
        ensureBucket()
    }

    override fun putQuarantine(key: String, content: InputStream): Long {
        validateKey(key)
        return content.use { source -> uploadMultipart(key, source) }
    }

    override fun openPrivate(key: String): InputStream {
        validateKey(key)
        return try {
            client.getObject(GetObjectRequest.builder().bucket(properties.bucket).key(key).build())
        } catch (_: Exception) {
            throw unavailable("read")
        }
    }

    override fun delete(key: String) {
        validateKey(key)
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket).key(key).build())
        } catch (_: Exception) {
            throw unavailable("delete")
        }
    }

    private fun ensureBucket() {
        val request = HeadBucketRequest.builder().bucket(properties.bucket).build()
        try {
            client.headBucket(request)
            return
        } catch (exception: S3Exception) {
            if (exception.statusCode() != 404 || !properties.createBucketIfMissing) {
                throw unavailable("bucket validation", exception.statusCode())
            }
        } catch (_: Exception) {
            throw unavailable("bucket validation")
        }

        try {
            client.createBucket(CreateBucketRequest.builder().bucket(properties.bucket).build())
            client.headBucket(request)
        } catch (exception: S3Exception) {
            throw unavailable("bucket creation", exception.statusCode())
        } catch (_: Exception) {
            throw unavailable("bucket creation")
        }
    }

    private fun uploadMultipart(key: String, content: InputStream): Long {
        val buffer = ByteArray(MULTIPART_CHUNK_BYTES)
        val firstPartSize = readPart(content, buffer)
        if (firstPartSize == 0) {
            try {
                client.putObject(
                    PutObjectRequest.builder().bucket(properties.bucket).key(key).build(),
                    RequestBody.empty(),
                )
                return 0
            } catch (_: Exception) {
                throw unavailable("upload")
            }
        }

        val uploadId = try {
            client.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(properties.bucket).key(key).build(),
            ).uploadId()
        } catch (_: Exception) {
            throw unavailable("upload initialization")
        }

        val completedParts = mutableListOf<CompletedPart>()
        var totalBytes = 0L
        var partNumber = 1
        var partSize = firstPartSize
        try {
            while (partSize > 0) {
                val response = client.uploadPart(
                    UploadPartRequest.builder()
                        .bucket(properties.bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength(partSize.toLong())
                        .build(),
                    RequestBody.fromInputStream(ByteArrayInputStream(buffer, 0, partSize), partSize.toLong()),
                )
                completedParts += CompletedPart.builder()
                    .partNumber(partNumber)
                    .eTag(response.eTag())
                    .build()
                totalBytes += partSize
                partNumber += 1
                partSize = readPart(content, buffer)
            }
            client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                    .bucket(properties.bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build(),
            )
            return totalBytes
        } catch (exception: Exception) {
            runCatching {
                client.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder()
                        .bucket(properties.bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .build(),
                )
            }
            if (exception is AttachmentTooLargeException) throw exception
            throw unavailable("upload")
        }
    }

    private fun readPart(content: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = content.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            if (read == 0) {
                val single = content.read()
                if (single < 0) break
                buffer[offset++] = single.toByte()
            } else {
                offset += read
            }
        }
        return offset
    }

    private fun validateKey(key: String) {
        require(key.matches(Regex("attachments/quarantine/[0-9a-f-]{36}"))) {
            "Invalid attachment object key"
        }
    }

    private fun unavailable(operation: String, statusCode: Int? = null): AttachmentUnavailableException {
        val suffix = statusCode?.let { " (status $it)" }.orEmpty()
        return AttachmentUnavailableException(IllegalStateException("S3 attachment $operation failed$suffix"))
    }

    private companion object {
        const val MULTIPART_CHUNK_BYTES = 8 * 1024 * 1024
    }
}
