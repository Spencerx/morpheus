/*
 * Copyright (c) 2016-2018 "Neo4j, Inc." [https://neo4j.com]
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
package org.opencypher.caps.api.io.conversion

import org.opencypher.caps.api.types.{CypherType, DefiniteCypherType}
import org.opencypher.caps.impl.exception.IllegalArgumentException

/**
  * Represents a map from node property keys to keys in the source data.
  */
trait EntityMapping {

  // TODO: CTEntity
  def cypherType: CypherType with DefiniteCypherType

  def sourceIdKey: String

  def propertyMapping: Map[String, String]

  protected def preventOverwritingProperty(propertyKey: String): Unit =
    if (propertyMapping.contains(propertyKey))
      throw IllegalArgumentException("unique property key definitions",
        s"given key $propertyKey overwrites existing mapping")

}