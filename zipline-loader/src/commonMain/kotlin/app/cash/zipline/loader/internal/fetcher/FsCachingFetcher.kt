/*
 * Copyright (C) 2022 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline.loader.internal.fetcher

import app.cash.zipline.EventListener
import app.cash.zipline.loader.ZiplineCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.ByteString

/**
 * Fetch from the network and save to local fileSystem cache once downloaded.
 */
internal class FsCachingFetcher(
  private val cache: ZiplineCache,
  private val cacheDispatcher: CoroutineDispatcher,
  private val delegate: Fetcher<ByteString>,
) : Fetcher<ByteString> {
  override suspend fun fetch(
    applicationName: String,
    eventListener: EventListener,
    id: String,
    sha256: ByteString,
    nowEpochMs: Long,
    baseUrl: String?,
    url: String,
  ): ByteString {
    return withContext(cacheDispatcher) {
      var downloadCalled = false
      val result = cache.getOrPut(applicationName, sha256, nowEpochMs) {
        downloadCalled = true
        delegate.fetch(applicationName, eventListener, id, sha256, nowEpochMs, baseUrl, url)
      }
      if (!downloadCalled) {
        eventListener.cacheHit(applicationName, url, result.size.toLong())
      }
      return@withContext result
    }
  }

  suspend fun loadPinnedManifest(applicationName: String, nowEpochMs: Long): LoadedManifest? {
    return withContext(cacheDispatcher) {
      cache.getPinnedManifest(applicationName, nowEpochMs)
    }
  }

  /**
   * Permits all downloads for [applicationName] not in [loadedManifest] to be pruned.
   *
   * This assumes that all artifacts in [loadedManifest] are currently pinned. Fetchers do not
   * necessarily enforce this assumption.
   */
  suspend fun pin(applicationName: String, loadedManifest: LoadedManifest, nowEpochMs: Long) {
    return withContext(cacheDispatcher) {
      cache.pinManifest(applicationName, loadedManifest, nowEpochMs)
    }
  }

  /**
   * Removes the pins for [applicationName] in [loadedManifest] so they may be pruned.
   */
  suspend fun unpin(applicationName: String, loadedManifest: LoadedManifest, nowEpochMs: Long) {
    return withContext(cacheDispatcher) {
      cache.unpinManifest(applicationName, loadedManifest, nowEpochMs)
    }
  }

  /**
   * Updates freshAt timestamp for manifests that in later network fetch is still the freshest.
   */
  suspend fun updateFreshAt(
    applicationName: String,
    loadedManifest: LoadedManifest,
    nowEpochMs: Long,
  ) {
    return withContext(cacheDispatcher) {
      cache.updateManifestFreshAt(applicationName, loadedManifest, nowEpochMs)
    }
  }
}
