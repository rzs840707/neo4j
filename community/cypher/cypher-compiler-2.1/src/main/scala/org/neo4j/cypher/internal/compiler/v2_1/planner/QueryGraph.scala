/**
 * Copyright (c) 2002-2014 "Neo Technology,"
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
package org.neo4j.cypher.internal.compiler.v2_1.planner

import org.neo4j.cypher.internal.compiler.v2_1.ast._
import org.neo4j.cypher.internal.compiler.v2_1.docbuilders.internalDocBuilder
import org.neo4j.cypher.internal.compiler.v2_1.helpers.UnNamedNameGenerator.isNamed
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.plans._

import scala.collection.GenTraversableOnce

trait QueryGraph {
  def patternRelationships: Set[PatternRelationship]
  def patternNodes: Set[IdName]
  def shortestPathPatterns: Set[ShortestPathPattern]

  def argumentIds: Set[IdName]
  def selections: Selections
  def optionalMatches: Seq[QueryGraph]
  def hints: Set[Hint]

  def addPatternNodes(nodes: IdName*): QueryGraph
  def addPatternRel(rel: PatternRelationship): QueryGraph
  def addPatternRels(rels: Seq[PatternRelationship]): QueryGraph
  def addShortestPath(shortestPath: ShortestPathPattern): QueryGraph
  def addShortestPaths(shortestPaths: ShortestPathPattern*): QueryGraph = shortestPaths.foldLeft(this)((qg, p) => qg.addShortestPath(p))
  def addArgumentId(newIds: Seq[IdName]): QueryGraph
  def addSelections(selections: Selections): QueryGraph
  def addPredicates(predicates: Expression*): QueryGraph
  def addHints(addedHints: GenTraversableOnce[Hint]): QueryGraph

  def withoutArguments(): QueryGraph = withArgumentIds(Set.empty)
  def withArgumentIds(argumentIds: Set[IdName]): QueryGraph

  def withAddedOptionalMatch(optionalMatch: QueryGraph): QueryGraph
  def withSelections(selections: Selections): QueryGraph

  def knownLabelsOnNode(node: IdName): Seq[LabelName] =
    selections
      .labelPredicates.getOrElse(node, Seq.empty)
      .flatMap(_.labels).toSeq

  def findRelationshipsEndingOn(id: IdName): Set[PatternRelationship] =
    patternRelationships.filter { r => r.left == id || r.right == id }

  def coveredIds: Set[IdName] = {
    val patternIds = QueryGraph.coveredIdsForPatterns(patternNodes, patternRelationships)
    val optionalMatchIds = optionalMatches.flatMap(_.coveredIds)
    patternIds ++ argumentIds ++ optionalMatchIds
  }

  val allHints: Set[Hint] =
    if (optionalMatches.isEmpty) hints else hints ++ optionalMatches.flatMap(_.allHints)

  def numHints = allHints.size

  def ++(other: QueryGraph): QueryGraph =
    QueryGraph(
      selections = selections ++ other.selections,
      patternNodes = patternNodes ++ other.patternNodes,
      patternRelationships = patternRelationships ++ other.patternRelationships,
      optionalMatches = optionalMatches ++ other.optionalMatches,
      argumentIds = argumentIds ++ other.argumentIds,
      hints = hints ++ other.hints,
      shortestPathPatterns = shortestPathPatterns ++ other.shortestPathPatterns
    )

  def isCoveredBy(other: QueryGraph): Boolean = {
    patternNodes.subsetOf(other.patternNodes) &&
      patternRelationships.subsetOf(other.patternRelationships) &&
      argumentIds.subsetOf(other.argumentIds) &&
      optionalMatches.toSet.subsetOf(other.optionalMatches.toSet) &&
      selections.predicates.subsetOf(other.selections.predicates) &&
      shortestPathPatterns.subsetOf(other.shortestPathPatterns)
  }

  def covers(other: QueryGraph): Boolean = other.isCoveredBy(this)

  def hasOptionalPatterns = optionalMatches.nonEmpty
}

object QueryGraph {
  def apply(patternRelationships: Set[PatternRelationship] = Set.empty,
            patternNodes: Set[IdName] = Set.empty,
            argumentIds: Set[IdName] = Set.empty,
            selections: Selections = Selections(),
            optionalMatches: Seq[QueryGraph] = Seq.empty,
            hints: Set[Hint] = Set.empty,
            shortestPathPatterns: Set[ShortestPathPattern] = Set.empty): QueryGraph =
    QueryGraphImpl(patternRelationships, patternNodes, argumentIds, selections, optionalMatches, hints, shortestPathPatterns)

  val empty = QueryGraph()

  def coveredIdsForPatterns(patternNodeIds: Set[IdName], patternRels: Set[PatternRelationship]) = {
    val patternRelIds = patternRels.flatMap(_.coveredIds)
    patternNodeIds ++ patternRelIds
  }
}


// An abstract representation of the query graph being solved at the current step
case class QueryGraphImpl(patternRelationships: Set[PatternRelationship] = Set.empty,
                          patternNodes: Set[IdName] = Set.empty,
                          argumentIds: Set[IdName] = Set.empty,
                          selections: Selections = Selections(),
                          optionalMatches: Seq[QueryGraph] = Seq.empty,
                          hints: Set[Hint] = Set.empty,
                          shortestPathPatterns: Set[ShortestPathPattern] = Set.empty)
  extends QueryGraph with internalDocBuilder.AsPrettyToString {

  def withAddedOptionalMatch(optionalMatch: QueryGraph): QueryGraph = {
    val argumentIds = coveredIds intersect optionalMatch.coveredIds
    copy(optionalMatches = optionalMatches :+ optionalMatch.addArgumentId(argumentIds.toSeq))
  }

  def addPatternNodes(nodes: IdName*): QueryGraph = copy(patternNodes = patternNodes ++ nodes)

  def addPatternRel(rel: PatternRelationship): QueryGraph =
    copy(
      patternNodes = patternNodes + rel.nodes._1 + rel.nodes._2,
      patternRelationships = patternRelationships + rel
    )

  def addPatternRels(rels: Seq[PatternRelationship]) =
    rels.foldLeft[QueryGraph](this)((qg, rel) => qg.addPatternRel(rel))

  def addShortestPath(shortestPath: ShortestPathPattern): QueryGraph = {
    val rel = shortestPath.rel
    copy (
      patternNodes = patternNodes + rel.nodes._1 + rel.nodes._2,
      shortestPathPatterns = shortestPathPatterns + shortestPath
    )
  }

  def addArgumentId(newIds: Seq[IdName]): QueryGraph = copy(argumentIds = argumentIds ++ newIds)

  def withArgumentIds(newArgumentIds: Set[IdName]): QueryGraph =
    copy(argumentIds = newArgumentIds)

  def withSelections(selections: Selections): QueryGraph = copy(selections = selections)

  def addSelections(selections: Selections): QueryGraph =
    copy(selections = Selections(selections.predicates ++ this.selections.predicates))

  def addPredicates(predicates: Expression*): QueryGraph = {
    val newSelections = Selections(predicates.flatMap(SelectionPredicates.extractPredicates).toSet)
    copy(selections = selections ++ newSelections)
  }

  def addHints(addedHints: GenTraversableOnce[Hint]) = copy(hints = hints ++ addedHints)
}

object SelectionPredicates {
  def fromWhere(where: Where): Set[Predicate] = extractPredicates(where.expression)

  def idNames(predicate: Expression): Set[IdName] = predicate.treeFold(Set.empty[IdName]) {
    case p: FilteringExpression =>
      (acc, _) => acc ++ (idNames(p.expression) ++ p.innerPredicate.map(idNames).getOrElse(Set.empty) - IdName(p.identifier.name))
    case Identifier(name) =>
      (acc, _) => acc + IdName(name)
  }

  def extractPredicates(predicate: Expression): Set[Predicate] = predicate.treeFold(Set.empty[Predicate]) {
    // n:Label
    case p@HasLabels(Identifier(name), labels) =>
      (acc, _) => acc ++ labels.map {
        label: LabelName =>
          Predicate(Set(IdName(name)), p.copy(labels = Seq(label))(p.position))
      }
    // and
    case _: Ands =>
      (acc, children) => children(acc)
    case p: Expression =>
      (acc, _) => acc + Predicate(idNames(p), p)
  }.map(filterUnnamed).toSet

  private def filterUnnamed(predicate: Predicate): Predicate = predicate match {
    case Predicate(deps, e: PatternExpression) =>
      Predicate(deps.filter(x => isNamed(x.name)), e)
    case Predicate(deps, e@Not(_: PatternExpression)) =>
      Predicate(deps.filter(x => isNamed(x.name)), e)
    case Predicate(deps, ors@Ors(exprs)) =>
      val newDeps = exprs.foldLeft(Set.empty[IdName]) { (acc, exp) =>
        exp match {
          case exp: PatternExpression =>
            acc ++ SelectionPredicates.idNames(exp).filter(x => isNamed(x.name))
          case exp@Not(_: PatternExpression) =>
            acc ++ SelectionPredicates.idNames(exp).filter(x => isNamed(x.name))
          case exp if exp.exists { case _: PatternExpression => true} =>
            acc ++ (SelectionPredicates.idNames(exp) -- unnamedIdNamesInNestedPatternExpressions(exp))
          case exp =>
            acc ++ SelectionPredicates.idNames(exp)
        }
      }
      Predicate(newDeps, ors)
    case Predicate(deps, expr) if expr.exists { case _: PatternExpression => true} =>
      Predicate(deps -- unnamedIdNamesInNestedPatternExpressions(expr), expr)
    case p => p
  }

  private def unnamedIdNamesInNestedPatternExpressions(expression: Expression) = {
    val patternExpressions = expression.treeFold(Seq.empty[PatternExpression]) {
      case p: PatternExpression => (acc, _) => acc :+ p
    }

    val unnamedIdsInPatternExprs = patternExpressions.flatMap(idNames)
      .filterNot(x => isNamed(x.name))
      .toSet

    unnamedIdsInPatternExprs
  }
}
