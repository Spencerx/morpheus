package org.opencypher.spark.impl.instances

import org.opencypher.spark.SparkCypherTestSuite
import org.opencypher.spark.api.value.CypherMap

import scala.collection.Bag

class OptionalMatchAcceptanceTest extends SparkCypherTestSuite {

  test("optional match") {
    // Given
    val given = TestGraph(
      """
        |(p1:Person {name: "Alice"}),
        |(p2:Person {name: "Bob"}),
        |(p3:Person {name: "Eve"}),
        |(p1)-[:KNOWS]->(p2),
        |(p2)-[:KNOWS]->(p3),
      """.stripMargin)

    // When
    val result = given.cypher(
      """
        |MATCH (p1:Person)
        |OPTIONAL MATCH (p1)-[e1]->(p2)-[e2]->(p3)
        |RETURN p1.name, p2.name, p3.name
      """.stripMargin)

    // Then
    result.records.toMaps should equal(Bag(
      CypherMap(
        "p1.name" -> "Eve",
        "p2.name" -> null,
        "p3.name" -> null
      ),
      CypherMap(
        "p1.name" -> "Bob",
        "p2.name" -> null,
        "p3.name" -> null
      ),
      CypherMap(
        "p1.name" -> "Alice",
        "p2.name" -> "Bob",
        "p3.name" -> "Eve"
      )
    ))
    result.graph shouldMatch given.graph
  }

  test("optional match with predicates") {
    // Given
    val given = TestGraph(
      """
        |(p1:Person {name: "Alice"}),
        |(p2:Person {name: "Bob"}),
        |(p1)-[:KNOWS]->(p2),
      """.stripMargin)

    // When
    val result = given.cypher(
      """
        |MATCH (p1:Person)
        |OPTIONAL MATCH (p1)-[e1:KNOWS]->(p2:Person)
        |RETURN p1.name, p2.name
      """.stripMargin)

    // Then
    result.records.toMaps should equal(Bag(
      CypherMap(
        "p1.name" -> "Bob",
        "p2.name" -> null
      ),
      CypherMap(
        "p1.name" -> "Alice",
        "p2.name" -> "Bob"
      )
    ))
    result.graph shouldMatch given.graph
  }

  test("optional match with partial matches") {
    // Given
    val given = TestGraph(
      """
        |(p1:Person {name: "Alice"}),
        |(p2:Person {name: "Bob"}),
        |(p3:Person {name: "Eve"}),
        |(p1)-[:KNOWS]->(p2),
        |(p2)-[:KNOWS]->(p3)
      """.stripMargin)

    // When
    val result = given.cypher(
      """
        |MATCH (p1:Person)
        |OPTIONAL MATCH (p1)-[e1:KNOWS]->(p2:Person)-[e2:KNOWS]->(p3:Person)
        |RETURN p1.name, p2.name, p3.name
      """.stripMargin)

    // Then
    result.records.toMaps should equal(Bag(
      CypherMap(
        "p1.name" -> "Alice",
        "p2.name" -> "Bob",
        "p3.name" -> "Eve"
      ),
      CypherMap(
        "p1.name" -> "Bob",
        "p2.name" -> null,
        "p3.name" -> null
      ),
      CypherMap(
        "p1.name" -> "Eve",
        "p2.name" -> null,
        "p3.name" -> null
      )
    ))
    result.graph shouldMatch given.graph
  }

  test("optional match with duplicates") {
    // Given
    val given = TestGraph(
      """
        |(p1:Person {name: "Alice"}),
        |(p2:Person {name: "Bob"}),
        |(p3:Person {name: "Eve"}),
        |(p4:Person {name: "Paul"}),
        |(p1)-[:KNOWS]->(p3),
        |(p2)-[:KNOWS]->(p3),
        |(p3)-[:KNOWS]->(p4),
      """.stripMargin)

    // When
    val result = given.cypher(
      """
        |MATCH (a:Person)-[e1:KNOWS]->(b:Person)
        |OPTIONAL MATCH (b)-[e2:KNOWS]->(c:Person)
        |RETURN b.name, c.name
      """.stripMargin)

    // Then
    result.records.toMaps should equal(Bag(
      CypherMap(
        "b.name" -> "Eve",
        "c.name" -> "Paul"
      ),
      CypherMap(
        "b.name" -> "Eve",
        "c.name" -> "Paul"
      ),
      CypherMap(
        "b.name" -> "Paul",
        "c.name" -> null
      )
    ))
    result.graph shouldMatch given.graph
  }
}
