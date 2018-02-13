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
package org.opencypher.caps.impl.spark.io.session

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

import org.opencypher.caps.api.CAPSSession
import org.opencypher.caps.impl.exception.UnsupportedOperationException
import org.opencypher.caps.api.graph.CypherSession
import org.opencypher.caps.impl.exception.{IllegalArgumentException, UnsupportedOperationException}
import org.opencypher.caps.impl.spark.io.{CAPSPropertyGraphDataSourceFactoryImpl, _}

import scala.collection.JavaConversions._

case object SessionPropertyGraphDataSourceFactory extends CAPSGraphSourceFactoryCompanion(CypherSession.sessionGraphSchema)

case class SessionPropertyGraphDataSourceFactory()
    extends CAPSPropertyGraphDataSourceFactoryImpl(SessionPropertyGraphDataSourceFactory) {

  val mountPoints: collection.concurrent.Map[String, CAPSPropertyGraphDataSource] = {
    new ConcurrentHashMap[String, CAPSPropertyGraphDataSource]()
  }

  def mountSourceAt(existingSource: CAPSPropertyGraphDataSource, uri: URI)(implicit capsSession: CypherSession): Unit =
    if (schemes.contains(uri.getScheme))
      withValidPath(uri) { (path: String) =>
        mountPoints.get(path) match {
          case Some(source) =>
            throw UnsupportedOperationException(s"Overwriting session graph at $source")

          case _ =>
            mountPoints.put(path, existingSource)
        }
      } else throw IllegalArgumentException(s"supported scheme: ${schemes.mkString("[", ", ", "]")}", uri.getScheme)

  def unmountAll(implicit capsSession: CypherSession): Unit =
    mountPoints.clear()

  override protected def sourceForURIWithSupportedScheme(uri: URI)(implicit capsSession: CAPSSession): CAPSPropertyGraphDataSource =
    withValidPath(uri) { (path: String) =>
      mountPoints.get(path) match {
        case Some(source) =>
          source

        case _ =>
          val newSource = SessionPropertyGraphDataSource(path)
          mountPoints.put(path, newSource)
          newSource
      }
    }

  private def withValidPath[T](uri: URI)(f: String => T): T = {
    val path = uri.getPath
    if (uri.getUserInfo != null ||
        uri.getHost != null ||
        uri.getPort != -1 ||
        uri.getQuery != null ||
        uri.getAuthority != null ||
        uri.getFragment != null ||
        path == null ||
        !path.startsWith("/"))
      throw IllegalArgumentException(s"a valid URI for use by $name", uri)
    else
      f(path)
  }
}