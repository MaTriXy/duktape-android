/*
 * Copyright (C) 2023 Cash App
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
package app.cash.zipline

import app.cash.zipline.internal.bridge.OutboundService

interface ZiplineServiceType<T : ZiplineService> {
  val name: String
  val functions: List<ZiplineFunction<T>>
}

/**
 * Returns the type of this service as described by the peer. Returns null if this service isn't an
 * outbound service, if it is closed, or if it is unknown to the remote peer.
 */
val <T : ZiplineService> T.targetType: ZiplineServiceType<T>? get() {
  if (this !is OutboundService) return null
  return callHandler.targetType as ZiplineServiceType<T>?
}

/**
 * Returns the type of this service according to its local declaration. Returns null if this service
 * isn't an outbound service.
 */
val <T : ZiplineService> T.sourceType: ZiplineServiceType<T>? get() {
  if (this !is OutboundService) return null
  return callHandler.sourceType as ZiplineServiceType<T>?
}
