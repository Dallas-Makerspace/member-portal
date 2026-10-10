package org.dallasmakerspace.askai.bedrock

import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory

private const val LOG_PREVIEW_LENGTH = 50

class MockEmbeddingClient @Inject constructor(loggerFactory: LoggerFactory) : IEmbeddingClient {
  private val log = loggerFactory.create(javaClass)

  override suspend fun embed(text: String): FloatArray? {
    log.info("[MOCK] Skipping embedding generation for: ${text.take(LOG_PREVIEW_LENGTH)}")
    return null
  }
}
