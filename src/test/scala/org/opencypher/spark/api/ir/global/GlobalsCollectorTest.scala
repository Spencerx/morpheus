package org.opencypher.spark.api.ir.global

import org.opencypher.spark.StdTestSuite
import org.opencypher.spark.impl.ir.GlobalsExtractor

class GlobalsCollectorTest extends StdTestSuite {

  import org.opencypher.spark.impl.parse.{CypherParser => parse}
  import parse.defaultContext

  test("collect tokens") {
    val given = parse("MATCH (a:Person)-[r:KNOWS]->(b:Duck) RETURN a.name, r.since, b.quack")
    val actual = GlobalsExtractor(given)
    val expected = GlobalsRegistry
      .none
      .withLabel(Label("Duck"))
      .withLabel(Label("Person"))
      .withRelType(RelType("KNOWS"))
      .withPropertyKey(PropertyKey("name"))
      .withPropertyKey(PropertyKey("since"))
      .withPropertyKey(PropertyKey("quack"))

    actual should equal(expected)
  }

  test("collect parameters") {
    val given = parse("WITH $param AS p RETURN p, $another")
    val actual = GlobalsExtractor(given)
    val expected = GlobalsRegistry.none.withConstant(Constant("param")).withConstant(Constant("another"))

    actual should equal(expected)
  }
}




