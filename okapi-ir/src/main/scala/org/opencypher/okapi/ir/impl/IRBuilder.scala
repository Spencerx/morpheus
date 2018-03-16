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
 *
 * Attribution Notice under the terms of the Apache License 2.0
 *
 * This work was created by the collective efforts of the openCypher community.
 * Without limiting the terms of Section 6, any Derivative Work that is not
 * approved by the public consensus process of the openCypher Implementers Group
 * should not be described as “Cypher” (and Cypher® is a registered trademark of
 * Neo4j Inc.) or as "openCypher". Extensions by implementers or prototypes or
 * proposals for change that have been documented or implemented should only be
 * described as "implementation extensions to Cypher" or as "proposed changes to
 * Cypher that are not yet approved by the openCypher community".
 */
package org.opencypher.okapi.ir.impl

import cats.implicits._
import org.atnos.eff._
import org.atnos.eff.all._
import org.neo4j.cypher.internal.frontend.v3_4.ast
import org.neo4j.cypher.internal.util.v3_4.InputPosition
import org.neo4j.cypher.internal.v3_4.{expressions => exp}
import org.opencypher.okapi.api.graph.QualifiedGraphName
import org.opencypher.okapi.api.schema.{PropertyKeys, Schema}
import org.opencypher.okapi.api.types._
import org.opencypher.okapi.impl.exception.{IllegalArgumentException, IllegalStateException, UnsupportedOperationException}
import org.opencypher.okapi.ir.api._
import org.opencypher.okapi.ir.api.block.{SortItem, _}
import org.opencypher.okapi.ir.api.expr._
import org.opencypher.okapi.ir.api.pattern.Pattern
import org.opencypher.okapi.ir.api.set.{SetItem, SetLabelItem, SetPropertyItem}
import org.opencypher.okapi.ir.api.util.CompilationStage
import org.opencypher.okapi.ir.impl.refactor.instances._

object IRBuilder extends CompilationStage[ast.Statement, CypherQuery[Expr], IRBuilderContext] {

  override type Out = Either[IRBuilderError, (Option[CypherQuery[Expr]], IRBuilderContext)]

  override def process(input: ast.Statement)(implicit context: IRBuilderContext): Out =
    buildIR[IRBuilderStack[Option[CypherQuery[Expr]]]](input).run(context)

  override def extract(output: Out): CypherQuery[Expr] =
    output match {
      case Left(error) => throw IllegalStateException(s"Error during IR construction: $error")
      case Right((Some(q), _)) => q
      case Right((None, _)) => throw IllegalStateException(s"Failed to construct IR")
    }

  private def buildIR[R: _mayFail : _hasContext](s: ast.Statement): Eff[R, Option[CypherQuery[Expr]]] =
    s match {
      case ast.Query(_, part) =>
        for {
          query <- {
            part match {
              case ast.SingleQuery(clauses) =>
                val blocks = clauses.toList.traverse(convertClause[R])
                blocks >> convertRegistry

              case x =>
                error(IRBuilderError(s"Query not supported: $x"))(None)
            }
          }
        } yield query

      case x =>
        error(IRBuilderError(s"Statement not yet supported: $x"))(None)
    }

  private def convertClause[R: _mayFail : _hasContext](c: ast.Clause): Eff[R, List[Block[Expr]]] = {

    c match {

      case ast.UseGraph(qgn: ast.QualifiedGraphName) =>
        for {
          context <- get[R, IRBuilderContext]
          blocks <- {
            val irQgn = QualifiedGraphName(qgn.parts)
            val ds = context.resolver(irQgn.namespace)
            val schema = ds.schema(irQgn.graphName) match {
              case Some(s) => s
              case None => ds.graph(irQgn.graphName).schema
            }
            val irGraph = IRCatalogGraph(irQgn, schema)
            val updatedContext = context.withWorkingGraph(irGraph)
            put[R, IRBuilderContext](updatedContext) >> pure[R, List[Block[Expr]]](List.empty)
          }
        } yield blocks

      case ast.Match(optional, pattern, _, astWhere) =>
        for {
          pattern <- convertPattern(pattern)
          given <- convertWhere(astWhere)
          context <- get[R, IRBuilderContext]
          blocks <- {
            val blockRegistry = context.blockRegistry
            val after = blockRegistry.lastAdded.toList
            val block = MatchBlock[Expr](after, pattern, given, optional, context.workingGraph)

            val typedOutputs = typedMatchBlock.outputs(block)
            val updatedRegistry = blockRegistry.register(block)
            val updatedContext = context.withBlocks(updatedRegistry).withFields(typedOutputs)
            put[R, IRBuilderContext](updatedContext) >> pure[R, List[Block[Expr]]](List(block))
          }
        } yield blocks

      case ast.With(distinct, ast.ReturnItems(_, items), orderBy, skip, limit, where)
        if !items.exists(_.expression.containsAggregate) =>
        for {
          fieldExprs <- items.toList.traverse(convertReturnItem[R])
          given <- convertWhere(where)
          context <- get[R, IRBuilderContext]
          refs <- {
            val (projectRef, projectReg) =
              registerProjectBlock(context, fieldExprs, given, context.workingGraph, distinct = distinct)
            val appendList = (list: List[Block[Expr]]) => pure[R, List[Block[Expr]]](projectRef +: list)
            val orderAndSliceBlock = registerOrderAndSliceBlock(orderBy, skip, limit)
            put[R, IRBuilderContext](context.copy(blockRegistry = projectReg)) >> orderAndSliceBlock flatMap appendList
          }
        } yield refs

      case ast.With(distinct, ast.ReturnItems(_, items), _, _, _, None) =>
        for {
          fieldExprs <- items.toList.traverse(convertReturnItem[R])
          context <- get[R, IRBuilderContext]
          blocks <- {
            val (agg, group) = fieldExprs.partition {
              case (_, _: Aggregator) => true
              case _ => false
            }

            val (projectBlock, updatedRegistry1) = registerProjectBlock(context, group, source = context.workingGraph, distinct = distinct)
            val after = updatedRegistry1.lastAdded.toList
            val aggregationBlock =
              AggregationBlock[Expr](after, Aggregations(agg.toSet), group.map(_._1).toSet, context.workingGraph)
            val updatedRegistry2 = updatedRegistry1.register(aggregationBlock)

            put[R, IRBuilderContext](context.copy(blockRegistry = updatedRegistry2)) >> pure[R, List[Block[Expr]]](List(projectBlock, aggregationBlock))
          }
        } yield blocks

      case ast.Unwind(listExpression, variable) =>
        for {
          tuple <- convertUnwindItem(listExpression, variable)
          context <- get[R, IRBuilderContext]
          block <- {
            val (list, item) = tuple
            val binds: UnwoundList[Expr] = UnwoundList(list, item)
            val block = UnwindBlock(context.blockRegistry.lastAdded.toList, binds, context.workingGraph)
            val updatedRegistry = context.blockRegistry.register(block)

            put[R, IRBuilderContext](context.copy(blockRegistry = updatedRegistry)) >> pure[R, List[Block[Expr]]](List(block))
          }
        } yield block

      // TODO: Support merges, removes
      case ast.ConstructGraph(Nil, creates, Nil, sets) =>
        for {
          patterns <- creates.map { case ast.Create(p: exp.Pattern) => p }.traverse(convertPattern[R])
          setItems <- sets.flatMap { case ast.SetClause(s: Seq[ast.SetItem]) => s }.traverse(convertSetItem[R])
          context <- get[R, IRBuilderContext]
          refs <- {
            val pattern = patterns.foldLeft(Pattern.empty[Expr])(_ ++ _)
            val fieldNamesInPattern = pattern.fields.map(_.name)
            val patternSchema = context.workingGraph.schema.forPattern(pattern)
            val (schema, _) = setItems.foldLeft(patternSchema -> Map.empty[Var, CypherType]) { case ((currentSchema, rewrittenVarTypes), setItem: SetItem[Expr]) =>
              if (!fieldNamesInPattern.contains(setItem.variable.name)) {
                throw UnsupportedOperationException("SET on a variable that is not defined inside of the CONSTRUCT scope")
              }
              setItem match {
                case SetLabelItem(variable, labels) =>
                  val existingLabels = rewrittenVarTypes.getOrElse(variable, variable.cypherType) match {
                    case CTNode(existing) => existing
                    case other => throw UnsupportedOperationException(s"SET label on something that is not a node: $other")
                  }
                  val labelsAfterSet = existingLabels ++ labels
                  val updatedSchema = currentSchema.addLabelsToCombo(labels, existingLabels)
                  updatedSchema -> rewrittenVarTypes.updated(variable, CTNode(labelsAfterSet))
                case SetPropertyItem(propertyKey, variable, setValue) =>
                  val propertyType = setValue.cypherType
                  val updatedSchema = currentSchema.addPropertyToEntity(propertyKey, propertyType, variable.cypherType)
                  updatedSchema -> rewrittenVarTypes
              }
            }
            val patternGraph = IRPatternGraph[Expr](schema, pattern, setItems.collect { case p: SetPropertyItem[Expr] => p })
            val updatedContext = context.withWorkingGraph(patternGraph)
            put[R, IRBuilderContext](updatedContext) >> pure[R, List[Block[Expr]]](List.empty)
          }
        } yield refs

      case ast.ReturnGraph(qgnOpt) =>
        for {
          context <- get[R, IRBuilderContext]
          refs <- {
            val after = context.blockRegistry.lastAdded.toList
            val irGraph = qgnOpt match {
              case None => context.workingGraph
              case Some(astQgn) =>
                val irQgn = QualifiedGraphName(astQgn.parts)
                val pgds = context.resolver(irQgn.namespace)
                IRCatalogGraph(irQgn, pgds.schema(irQgn.graphName).getOrElse(pgds.graph(irQgn.graphName).schema))
            }
            val returns = GraphResultBlock[Expr](after, irGraph)
            val updatedRegistry = context.blockRegistry.register(returns)
            put[R, IRBuilderContext](context.copy(blockRegistry = updatedRegistry)) >> pure[R, List[Block[Expr]]](List(returns))
          }
        } yield refs

      case ast.Return(distinct, ast.ReturnItems(_, items), orderBy, skip, limit, _) =>
        for {
          fieldExprs <- items.toList.traverse(convertReturnItem[R])
          context <- get[R, IRBuilderContext]
          blocks1 <- {
            val (projectRef, projectReg) =
              registerProjectBlock(context, fieldExprs, distinct = distinct, source = context.workingGraph)
            val appendList = (list: List[Block[Expr]]) => pure[R, List[Block[Expr]]](projectRef +: list)
            val orderAndSliceBlock = registerOrderAndSliceBlock(orderBy, skip, limit)
            put[R, IRBuilderContext](context.copy(blockRegistry = projectReg)) >> orderAndSliceBlock flatMap appendList
          }
          context2 <- get[R, IRBuilderContext]
          blocks2 <- {
            val rItems = fieldExprs.map(_._1)
            val orderedFields = OrderedFields[Expr](rItems)
            val resultBlock = TableResultBlock[Expr](List(blocks1.last), orderedFields, context.workingGraph)
            val updatedRegistry = context2.blockRegistry.register(resultBlock)
            put[R, IRBuilderContext](context.copy(blockRegistry = updatedRegistry)) >> pure[R, List[Block[Expr]]](blocks1 :+ resultBlock)
          }
        } yield blocks2

      case x =>
        error(IRBuilderError(s"Clause not yet supported: $x"))(List.empty[Block[Expr]])
    }
  }

  private def registerProjectBlock(
    context: IRBuilderContext,
    fieldExprs: List[(IRField, Expr)],
    given: List[Expr] = List.empty[Expr],
    source: IRGraph,
    distinct: Boolean): (Block[Expr], BlockRegistry[Expr]) = {
    val blockRegistry = context.blockRegistry
    val binds = Fields(fieldExprs.toMap)

    val after = blockRegistry.lastAdded.toList
    val projs = ProjectBlock[Expr](after, binds, given, source, distinct)

    projs -> blockRegistry.register(projs)
  }

  private def registerOrderAndSliceBlock[R: _mayFail : _hasContext](
    orderBy: Option[ast.OrderBy],
    skip: Option[ast.Skip],
    limit: Option[ast.Limit]) = {
    for {
      context <- get[R, IRBuilderContext]
      sortItems <- orderBy match {
        case Some(ast.OrderBy(sortItems)) =>
          sortItems.toList.traverse(convertSortItem[R])
        case None => List[ast.SortItem]().traverse(convertSortItem[R])
      }
      skipExpr <- convertExpr(skip.map(_.expression))
      limitExpr <- convertExpr(limit.map(_.expression))

      blocks <- {
        if (sortItems.isEmpty && skipExpr.isEmpty && limitExpr.isEmpty) pure[R, List[Block[Expr]]](List())
        else {
          val blockRegistry = context.blockRegistry
          val after = blockRegistry.lastAdded.toList

          val orderAndSliceBlock = OrderAndSliceBlock[Expr](after, sortItems, skipExpr, limitExpr, context.workingGraph)
          val updatedRegistry = blockRegistry.register(orderAndSliceBlock)
          put[R, IRBuilderContext](context.copy(blockRegistry = updatedRegistry)) >> pure[R, List[Block[Expr]]](List(orderAndSliceBlock))
        }
      }
    } yield blocks
  }

  private def convertReturnItem[R: _mayFail : _hasContext](item: ast.ReturnItem): Eff[R, (IRField, Expr)] = item match {

    case ast.AliasedReturnItem(e, v) =>
      for {
        expr <- convertExpr(e)
        context <- get[R, IRBuilderContext]
        field <- {
          val field = IRField(v.name)(expr.cypherType)
          put[R, IRBuilderContext](context.withFields(Set(field))) >> pure[R, IRField](field)
        }
      } yield field -> expr

    case ast.UnaliasedReturnItem(e, name) =>
      for {
        expr <- convertExpr(e)
        context <- get[R, IRBuilderContext]
        field <- {
          val field = IRField(name)(expr.cypherType)
          put[R, IRBuilderContext](context.withFields(Set(field))) >> pure[R, IRField](field)
        }
      } yield field -> expr

    case _ =>
      throw IllegalArgumentException(s"${ast.AliasedReturnItem.getClass} or ${ast.UnaliasedReturnItem.getClass}", item.getClass)
  }

  private def convertUnwindItem[R: _mayFail : _hasContext](
    list: exp.Expression,
    variable: exp.Variable): Eff[R, (Expr, IRField)] = {
    for {
      expr <- convertExpr(list)
      context <- get[R, IRBuilderContext]
      typ <- expr.cypherType.material match {
        case CTList(inner) =>
          pure[R, CypherType](inner)
        case CTAny =>
          pure[R, CypherType](CTAny)
        case x =>
          error(IRBuilderError(s"unwind expression was not a list: $x"))(CTWildcard: CypherType)
      }
      field <- {
        val field = IRField(variable.name)(typ)
        put[R, IRBuilderContext](context.withFields(Set(field))) >> pure[R, (Expr, IRField)](expr -> field)
      }
    } yield field
  }

  private def convertPattern[R: _hasContext](p: exp.Pattern): Eff[R, Pattern[Expr]] = {
    for {
      context <- get[R, IRBuilderContext]
      result <- {
        val pattern = context.convertPattern(p)
        val patternTypes = pattern.fields.foldLeft(context.knownTypes) {
          case (acc, f) => acc.updated(exp.Variable(f.name)(InputPosition.NONE), f.cypherType)
        }
        put[R, IRBuilderContext](context.copy(knownTypes = patternTypes)) >> pure[R, Pattern[Expr]](pattern)
      }
    } yield result
  }

  private def convertSetItem[R: _hasContext](p: ast.SetItem): Eff[R, SetItem[Expr]] = {
    p match {
      case ast.SetPropertyItem(exp.LogicalProperty(map: exp.Variable, exp.PropertyKeyName(propertyName)), setValue: exp.Expression) =>
        for {
          variable <- convertExpr[R](map)
          convertedSetExpr <- convertExpr[R](setValue)
          result <- {
            val setItem = SetPropertyItem(propertyName, variable.asInstanceOf[Var], convertedSetExpr)
            pure[R, SetItem[Expr]](setItem)
          }
        } yield result
      case ast.SetLabelItem(expr, labels) =>
        for {
          variable <- convertExpr[R](expr)
          result <- {
            val setLabel: SetItem[Expr] = SetLabelItem(variable.asInstanceOf[Var], labels.map(_.name).toSet)
            pure[R, SetItem[Expr]](setLabel)
          }
        } yield result
    }
  }

  private def convertExpr[R: _mayFail : _hasContext](e: Option[exp.Expression]): Eff[R, Option[Expr]] =
    for {
      context <- get[R, IRBuilderContext]
    } yield
      e match {
        case Some(expr) => Some(context.convertExpression(expr))
        case None => None
      }

  private def convertExpr[R: _hasContext](e: exp.Expression): Eff[R, Expr] =
    for {
      context <- get[R, IRBuilderContext]
    } yield context.convertExpression(e)

  private def convertWhere[R: _hasContext](where: Option[ast.Where]): Eff[R, List[Expr]] = where match {
    case Some(ast.Where(expr)) =>
      for {
        predicate <- convertExpr(expr)
      } yield {
        predicate match {
          case org.opencypher.okapi.ir.api.expr.Ands(exprs) => exprs
          case e => List(e)
        }
      }

    case None =>
      pure[R, List[Expr]](List.empty[Expr])
  }

  private def convertRegistry[R: _mayFail : _hasContext]: Eff[R, Option[CypherQuery[Expr]]] =
    for {
      context <- get[R, IRBuilderContext]
    } yield {
      val blocks = context.blockRegistry
      val model = QueryModel(blocks.lastAdded.get.asInstanceOf[ResultBlock[Expr]], context.parameters)
      val info = QueryInfo(context.queryString)

      Some(CypherQuery(info, model))
    }

  private def convertSortItem[R: _mayFail : _hasContext](item: ast.SortItem): Eff[R, SortItem[Expr]] = {
    item match {
      case ast.AscSortItem(astExpr) =>
        for {
          expr <- convertExpr(astExpr)
        } yield Asc(expr)
      case ast.DescSortItem(astExpr) =>
        for {
          expr <- convertExpr(astExpr)
        } yield Desc(expr)
    }
  }
}
