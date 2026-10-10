package org.dallasmakerspace.askai.bedrock

/**
 * Interface for generating text embeddings. Used for semantic cache matching. Returns null if
 * embedding generation is not available or fails.
 */
interface IEmbeddingClient {
  suspend fun embed(text: String): FloatArray?
}
