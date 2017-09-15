/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.v3_3.logical.plans

import java.lang.reflect.Method

import org.neo4j.cypher.internal.frontend.v3_3.Foldable._
import org.neo4j.cypher.internal.frontend.v3_3.InternalException
import org.neo4j.cypher.internal.frontend.v3_3.Rewritable._
import org.neo4j.cypher.internal.frontend.v3_3.ast._
import org.neo4j.cypher.internal.ir.v3_3.{CardinalityEstimation, IdName, PlannerQuery, Strictness}

/*
A LogicalPlan is an algebraic query, which is represented by a query tree whose leaves are database relations and
non-leaf nodes are algebraic operators like selections, projections, and joins. An intermediate node indicates the
application of the corresponding operator on the relations generated by its children, the result of which is then sent
further up. Thus, the edges of a tree represent data flow from bottom to top, i.e., from the leaves, which correspond
to data in the database, to the root, which is the final operator producing the query answer. */
abstract class LogicalPlan
  extends Product
  with Strictness
  with RewritableWithMemory {

  self =>

  def lhs: Option[LogicalPlan]
  def rhs: Option[LogicalPlan]
  def solved: PlannerQuery with CardinalityEstimation
  def availableSymbols: Set[IdName]

  def assignedId: LogicalPlanId = _id.getOrElse(throw new InternalException("Plan has not had an id assigned yet"))
  def assignIds(): Unit = {
    if(_id.nonEmpty)
      throw new InternalException("Id has already been assigned")

    val builder = new IdAssigner
    builder.assignIds()
    assignedId
  }

  private var _id: Option[LogicalPlanId] = None

  private class IdAssigner extends TreeBuilder[Int] {
    def assignIds() = create(self)

    private var count = 0

    override protected def build(plan: LogicalPlan) = {
      plan._id = Some(new LogicalPlanId(count))
      count = count + 1
      count
    }

    override protected def build(plan: LogicalPlan, source: Int) = build(plan)

    override protected def build(plan: LogicalPlan, lhs: Int, rhs: Int) = build(plan)
  }

  override def rememberMe(old: AnyRef): Unit = _id = old.asInstanceOf[LogicalPlan]._id

  def leaves: Seq[LogicalPlan] = this.treeFold(Seq.empty[LogicalPlan]) {
    case plan: LogicalPlan
      if plan.lhs.isEmpty && plan.rhs.isEmpty => acc => (acc :+ plan, Some(identity))
  }

  def updateSolved(newSolved: PlannerQuery with CardinalityEstimation): LogicalPlan = {
    val arguments = this.children.toList :+ newSolved
    try {
      copyConstructor.invoke(this, arguments: _*).asInstanceOf[this.type]
    } catch {
      case e: IllegalArgumentException if e.getMessage.startsWith("wrong number of arguments") =>
        throw new InternalException("Logical plans need to be case classes, and have the PlannerQuery in a separate constructor")
    }
  }

  def copyPlan(): LogicalPlan = {
    try {
      val arguments = this.children.toList :+ solved
      copyConstructor.invoke(this, arguments: _*).asInstanceOf[this.type]
    } catch {
      case e: IllegalArgumentException if e.getMessage.startsWith("wrong number of arguments") =>
        throw new InternalException("Logical plans need to be case classes, and have the PlannerQuery in a separate constructor", e)
    }
  }

  lazy val copyConstructor: Method = this.getClass.getMethods.find(_.getName == "copy").get

  def updateSolved(f: PlannerQuery with CardinalityEstimation => PlannerQuery with CardinalityEstimation): LogicalPlan =
    updateSolved(f(solved))

  def dup(children: Seq[AnyRef]): this.type =
    if (children.iterator eqElements this.children)
      this
    else {
      val constructor = this.copyConstructor
      val params = constructor.getParameterTypes
      val args = children.toIndexedSeq
      if ((params.length == args.length + 1) && params.last.isAssignableFrom(classOf[PlannerQuery]))
        constructor.invoke(this, args :+ this.solved: _*).asInstanceOf[this.type]
      else
        constructor.invoke(this, args: _*).asInstanceOf[this.type]
    }

  def isLeaf: Boolean = lhs.isEmpty && rhs.isEmpty

  override def toString = {
    def indent(level: Int, in: String): String = level match {
      case 0 => in
      case _ => "\n" + "  " * level + in
    }

    val childrenHeap = new scala.collection.mutable.Stack[(String, Int, Option[LogicalPlan])]
    childrenHeap.push(("", 0, Some(this)))
    val sb = new StringBuilder()

    while (childrenHeap.nonEmpty) {
      childrenHeap.pop() match {
        case (prefix, level, Some(plan)) =>
          val children = plan.lhs.toIndexedSeq ++ plan.rhs.toIndexedSeq
          val nonChildFields = plan.productIterator.filterNot(children.contains).mkString(", ")
          val prodPrefix = plan.productPrefix
          sb.append(indent(level, s"""$prefix$prodPrefix($nonChildFields) {""".stripMargin))

          (plan.lhs, plan.rhs) match {
            case (None, None) =>
              sb.append("}")
            case (Some(l), None) =>
              childrenHeap.push(("\n" + "  " * level + "}", level + 1, None))
              childrenHeap.push(("LHS -> ", level + 1, plan.lhs))
            case _ =>
              childrenHeap.push(("\n" + "  " * level + "}", level + 1, None))
              childrenHeap.push(("RHS -> ", level + 1, plan.rhs))
              childrenHeap.push(("LHS -> ", level + 1, plan.lhs))
          }
        case (prefix, _, _) =>
          sb.append(prefix)
      }
    }

    sb.toString()
  }

  def satisfiesExpressionDependencies(e: Expression) = e.dependencies.map(IdName.fromVariable).forall(availableSymbols.contains)

  def debugId: String = f"0x${hashCode()}%08x"

  def flatten: Seq[LogicalPlan] = Flattener.create(this)

  def indexUsage: Seq[IndexUsage] = {
    import org.neo4j.cypher.internal.frontend.v3_3.Foldable._
    this.fold(Seq.empty[IndexUsage]) {
      case NodeIndexSeek(idName, label, propertyKeys, _, _) =>
        (acc) => acc :+ SchemaIndexSeekUsage(idName.name, label.nameId.id, label.name, propertyKeys.map(_.name))
      case NodeUniqueIndexSeek(idName, label, propertyKeys, _, _) =>
        (acc) => acc :+ SchemaIndexSeekUsage(idName.name, label.nameId.id, label.name, propertyKeys.map(_.name))
      case NodeIndexScan(idName, label, propertyKey, _) =>
        (acc) => acc :+ SchemaIndexScanUsage(idName.name, label.nameId.id, label.name, propertyKey.name)
      }
  }
}

abstract class LogicalLeafPlan extends LogicalPlan with LazyLogicalPlan {
  final val lhs = None
  final val rhs = None
  def argumentIds: Set[IdName]
}

abstract class NodeLogicalLeafPlan extends LogicalLeafPlan {
  def idName: IdName
}

abstract class IndexLeafPlan extends NodeLogicalLeafPlan {
  def valueExpr: QueryExpression[Expression]
}

case object Flattener extends TreeBuilder[Seq[LogicalPlan]] {
  override protected def build(plan: LogicalPlan): Seq[LogicalPlan] = Seq(plan)

  override protected def build(plan: LogicalPlan, source: Seq[LogicalPlan]): Seq[LogicalPlan] = plan +: source

  override protected def build(plan: LogicalPlan, lhs: Seq[LogicalPlan], rhs: Seq[LogicalPlan]): Seq[LogicalPlan] = (plan +: lhs) ++ rhs
}

sealed trait IndexUsage {
  def identifier:String
}

final case class SchemaIndexSeekUsage(identifier: String, labelId : Int, label: String, propertyKeys: Seq[String]) extends IndexUsage
final case class SchemaIndexScanUsage(identifier: String, labelId : Int, label: String, propertyKey: String) extends IndexUsage
final case class ExplicitNodeIndexUsage(identifier: String, index: String) extends IndexUsage
final case class ExplicitRelationshipIndexUsage(identifier: String, index: String) extends IndexUsage

class LogicalPlanId(val underlying: Int) extends AnyVal {
  def ++ : LogicalPlanId = new LogicalPlanId(underlying + 1)
}