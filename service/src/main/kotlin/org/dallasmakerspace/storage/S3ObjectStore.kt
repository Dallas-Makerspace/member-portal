package org.dallasmakerspace.storage

import java.net.URL
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.future.await
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

private const val HTTP_SERVER_ERROR = 500
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_NOT_FOUND = 404

/**
 * S3-backed [IObjectStore]. Uses [DefaultCredentialsProvider], same as `BedrockClient`. Bucket and
 * region come from `app.object-store.*` config.
 */
@Singleton
class S3ObjectStore
@Inject
constructor(
    private val appConfig: AppConfig,
    loggerFactory: LoggerFactory,
) : IObjectStore {

  private val log = loggerFactory.create(S3ObjectStore::class.java)

  private val bucket: String by lazy { appConfig.requireStringProperty("app.object-store.bucket") }

  private val region: Region by lazy {
    Region.of(appConfig.requireStringProperty("app.object-store.region"))
  }

  private val client: S3AsyncClient by lazy {
    S3AsyncClient.builder()
        .region(region)
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build()
  }

  private val presigner: S3Presigner by lazy {
    S3Presigner.builder()
        .region(region)
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build()
  }

  override suspend fun put(
      key: ObjectKey,
      bytes: ByteArray,
      contentType: String,
      metadata: Map<String, String>,
  ): StoredObject {
    val request =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key.value)
            .contentType(contentType)
            .metadata(metadata)
            .build()
    val started = Instant.now()
    try {
      client.putObject(request, AsyncRequestBody.fromBytes(bytes)).await()
      log.info("S3 put key=${key.value} size=${bytes.size} ms=${elapsedMs(started)}")
      return StoredObject(
          key = key,
          sizeBytes = bytes.size.toLong(),
          contentType = contentType,
          lastModified = Instant.now(),
          metadata = metadata,
      )
    } catch (e: SdkException) {
      log.warn("S3 put failed key=${key.value}", e)
      throw wrap("put", key, e)
    }
  }

  override suspend fun get(key: ObjectKey): ObjectContent? {
    val request = GetObjectRequest.builder().bucket(bucket).key(key.value).build()
    val started = Instant.now()
    return try {
      val response = client.getObject(request, AsyncResponseTransformer.toBytes()).await()
      log.info("S3 get key=${key.value} ms=${elapsedMs(started)}")
      ObjectContent(
          stored =
              StoredObject(
                  key = key,
                  sizeBytes =
                      response.response().contentLength() ?: response.asByteArray().size.toLong(),
                  contentType = response.response().contentType().orEmpty(),
                  lastModified = response.response().lastModified() ?: Instant.now(),
                  metadata = response.response().metadata().orEmpty(),
              ),
          bytes = response.asByteArray(),
      )
    } catch (e: SdkException) {
      if (isNotFound(e)) return null
      log.warn("S3 get failed key=${key.value}", e)
      throw wrap("get", key, e)
    }
  }

  override suspend fun head(key: ObjectKey): StoredObject? {
    val request = HeadObjectRequest.builder().bucket(bucket).key(key.value).build()
    return try {
      val response = client.headObject(request).await()
      StoredObject(
          key = key,
          sizeBytes = response.contentLength() ?: 0L,
          contentType = response.contentType().orEmpty(),
          lastModified = response.lastModified() ?: Instant.now(),
          metadata = response.metadata().orEmpty(),
      )
    } catch (e: SdkException) {
      if (isNotFound(e)) null else throw wrap("head", key, e)
    }
  }

  override suspend fun delete(key: ObjectKey) {
    val request = DeleteObjectRequest.builder().bucket(bucket).key(key.value).build()
    try {
      client.deleteObject(request).await()
      log.info("S3 delete key=${key.value}")
    } catch (e: SdkException) {
      throw wrap("delete", key, e)
    }
  }

  override suspend fun list(prefix: ObjectKey, limit: Int): List<StoredObject> {
    val request =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix.value).maxKeys(limit).build()
    return try {
      client.listObjectsV2(request).await().contents().map { obj ->
        StoredObject(
            key = ObjectKey(obj.key()),
            sizeBytes = obj.size() ?: 0L,
            contentType = "",
            lastModified = obj.lastModified() ?: Instant.now(),
        )
      }
    } catch (e: SdkException) {
      throw wrap("list", prefix, e)
    }
  }

  override fun presignedGetUrl(key: ObjectKey, ttl: Duration): URL {
    val getRequest = GetObjectRequest.builder().bucket(bucket).key(key.value).build()
    val presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(getRequest)
            .build()
    return presigner.presignGetObject(presignRequest).url()
  }

  private fun isNotFound(e: Throwable) =
      e is NoSuchKeyException || (e is AwsServiceException && e.statusCode() == HTTP_NOT_FOUND)

  private fun isRetryable(e: Throwable) =
      e is AwsServiceException &&
          (e.statusCode() >= HTTP_SERVER_ERROR || e.statusCode() == HTTP_TOO_MANY_REQUESTS)

  private fun wrap(op: String, key: ObjectKey, cause: Throwable): ObjectStoreException =
      ObjectStoreException(
          message = "S3 $op failed for key=${key.value}",
          cause = cause,
          isNotFound = isNotFound(cause),
          isRetryable = isRetryable(cause),
      )

  private fun elapsedMs(since: Instant) = Instant.now().toEpochMilli() - since.toEpochMilli()
}
