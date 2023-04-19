/*
 * Copyright 2019-2023 CloudNetService team & contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.cloudnetservice.driver.document;

/**
 * Defines the standard serialisation styles. All document implementations should at least support these serialisation
 * styles or silently convert them to a supported style. They should never throw an exception when a standard style is
 * passed to a serialisation method.
 *
 * @since 4.0
 */
public enum StandardSerialisationStyle implements SerialisationStyle {

  /**
   * Uses the most efficient way to pack the document data. This style is preferred when the document is not subject to
   * be read by a human (for example when transferred via the network).
   */
  COMPACT,
  /**
   * Uses a human-readable style to pack the data.
   */
  PRETTY
}