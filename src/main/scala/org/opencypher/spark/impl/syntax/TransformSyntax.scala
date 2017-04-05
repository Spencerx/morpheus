package org.opencypher.spark.impl.syntax

import org.opencypher.spark.api.expr.{Expr, Var}
import org.opencypher.spark.api.record.{ProjectedSlotContent, RecordSlot}
import org.opencypher.spark.impl.classes.Transform

import scala.language.implicitConversions

trait TransformSyntax {
  implicit def transformSyntax[T : Transform](subject: T): TransformOps[T] = new TransformOps(subject)
}

final class TransformOps[T](subject: T)(implicit transform: Transform[T]) {
  def filter(expr: Expr): T = transform.filter(subject, expr)
  def select(fields: Set[Var]): T = transform.select(subject, fields)
  def project(it: ProjectedSlotContent): T = transform.project(subject, it)
  def join(other: T)(lhs: RecordSlot, rhs: RecordSlot): T = transform.join(subject, other)(lhs, rhs)
}
