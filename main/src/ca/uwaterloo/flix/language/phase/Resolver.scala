/*
 *  Copyright 2017 Magnus Madsen
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{Ast, Kind, Name, NamedAst, ResolvedAst, Scheme, Symbol, Type}
import ca.uwaterloo.flix.language.errors.{ResolutionError, TypeError}
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import ca.uwaterloo.flix.util.Validation._
import ca.uwaterloo.flix.util.{Result, Validation}

import scala.collection.mutable

// TODO: Ensure that program is named prog0

// TODO: DOC
object Resolver extends Phase[NamedAst.Program, ResolvedAst.Program] {

  /**
    * Performs name resolution on the given program `prog0`.
    */
  def run(prog0: NamedAst.Program)(implicit flix: Flix): Validation[ResolvedAst.Program, ResolutionError] = {

    val b = System.nanoTime()

    val definitionsVal = prog0.definitions.map {
      case (ns, defs) => Declarations.resolveAll(defs, ns, prog0) map {
        case ds => ns -> ds
      }
    }

    val enumsVal = prog0.enums.map {
      case (ns, enums) => Declarations.resolveAllEnum(enums, ns, prog0) map {
        case es => ns -> es
      }
    }

    val latticesVal = prog0.lattices.map {
      case (tpe0, lattice0) =>
        for {
          tpe <- lookupType(tpe0, lattice0.ns, prog0)
          lattice <- Declarations.resolve(lattice0, lattice0.ns, prog0)
        } yield (tpe, lattice)
    }

    val indexesVal = prog0.indexes.map {
      case (ns, indexes) => Declarations.resolveAllIndexes(indexes, ns, prog0) map {
        case idxs => ns -> idxs
      }
    }

    val tablesVal = prog0.tables.map {
      case (ns, tables) => Declarations.resolveAllTables(tables, ns, prog0) map {
        case tbs => ns -> tbs
      }
    }

    val constraintsVal = prog0.constraints.map {
      case (ns, constraints) => Constraints.resolve(constraints, ns, prog0) map {
        case cs => ns -> cs
      }
    }

    val propertiesVal = prog0.properties.map {
      case (ns, properties) => Properties.resolve(properties, ns, prog0) map {
        case ps => ns -> ps
      }
    }

    // TODO: Add time
    val e = System.nanoTime() - b

    for {
      definitions <- seqM(definitionsVal)
      enums <- seqM(enumsVal)
      lattices <- seqM(latticesVal)
      indexes <- seqM(indexesVal)
      tables <- seqM(tablesVal)
      constraints <- seqM(constraintsVal)
      properties <- seqM(propertiesVal)
    } yield {

      // TODO: temporary hack
      val definitions2 = definitions.flatMap {
        case (ns, m) => m.map {
          case (_, defn) => defn.sym -> defn
        }
      }.toMap

      // TODO: temporary hack
      val enums2 = enums.flatMap {
        case (ns, m) => m.map {
          case (_, enum) => enum.sym -> enum
        }
      }.toMap

      // TODO: temporary hack
      val tables2 = tables.flatMap {
        case (ns, m) => m.map {
          case (_, table) => table.sym -> table
        }
      }.toMap

      ResolvedAst.Program(
        definitions2,
        enums2,
        tables2,
        definitions.toMap, enums.toMap, lattices.toMap, indexes.toMap, tables.toMap, constraints.toMap, prog0.hooks, properties.toMap, prog0.reachable, prog0.time)
    }
  }

  object Constraints {

    /**
      * Performs name resolution on the given `constraints` in the given namespace `ns0`.
      */
    def resolve(constraints: List[NamedAst.Constraint], ns0: Name.NName, prog0: NamedAst.Program): Validation[List[ResolvedAst.Constraint], ResolutionError] = {
      seqM(constraints.map(c => resolve(c, ns0, prog0)))
    }

    /**
      * Performs name resolution on the given constraint `c0` in the given namespace `ns0`.
      */
    def resolve(c0: NamedAst.Constraint, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Constraint, ResolutionError] = {
      for {
        ps <- seqM(c0.cparams.map(p => Params.resolve(p, ns0, prog0)))
        h <- Predicates.Head.resolve(c0.head, ns0, prog0)
        bs <- seqM(c0.body.map(b => Predicates.Body.resolve(b, ns0, prog0)))
      } yield ResolvedAst.Constraint(ps, h, bs, c0.loc)
    }

  }

  object Declarations {

    // TODO: Refactor
    def resolveAll(m0: Map[String, NamedAst.Declaration.Definition], ns0: Name.NName, prog0: NamedAst.Program): Validation[Map[String, ResolvedAst.Declaration.Definition], ResolutionError] = {
      val results = m0.map {
        case (name, defn) => resolve(defn, ns0, prog0) map {
          case d => name -> d
        }
      }
      seqM(results).map(_.toMap)
    }


    // TODO: Refactor
    def resolveAllEnum(m0: Map[String, NamedAst.Declaration.Enum], ns0: Name.NName, prog0: NamedAst.Program): Validation[Map[String, ResolvedAst.Declaration.Enum], ResolutionError] = {
      val results = m0.map {
        case (name, defn) => resolve(defn, ns0, prog0) map {
          case d => name -> d
        }
      }
      seqM(results).map(_.toMap)
    }


    // TODO: Refactor
    def resolveAllIndexes(m0: Map[String, NamedAst.Declaration.Index], ns0: Name.NName, prog0: NamedAst.Program): Validation[Map[String, ResolvedAst.Declaration.Index], ResolutionError] = {
      val results = m0.map {
        case (name, index) => resolve(index, ns0, prog0) map {
          case d => name -> d
        }
      }
      seqM(results).map(_.toMap)
    }

    // TODO: Refactor
    def resolveAllTables(m0: Map[String, NamedAst.Table], ns0: Name.NName, prog0: NamedAst.Program): Validation[Map[String, ResolvedAst.Table], ResolutionError] = {
      val results = m0.map {
        case (name, table) => Tables.resolve(table, ns0, prog0) map {
          case d => name -> d
        }
      }
      seqM(results).map(_.toMap)
    }

    /**
      * Performs name resolution on the given definition `d0` in the given namespace `ns0`.
      */
    def resolve(d0: NamedAst.Declaration.Definition, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Declaration.Definition, ResolutionError] = {
      val schemeVal = for {
        base <- lookupType(d0.sc.base, ns0, prog0)
      } yield Scheme(d0.sc.quantifiers, base)

      for {
        tparams <- seqM(d0.tparams.map(tparam => Params.resolve(tparam, ns0, prog0)))
        fparams <- seqM(d0.fparams.map(fparam => Params.resolve(fparam, ns0, prog0)))
        e <- Expressions.resolve(d0.exp, ns0, prog0)
        sc <- schemeVal
      } yield ResolvedAst.Declaration.Definition(d0.doc, d0.ann, d0.sym, tparams, fparams, e, sc, d0.loc)

    }

    /**
      * Performs name resolution on the given enum `e0` in the given namespace `ns0`.
      */
    def resolve(e0: NamedAst.Declaration.Enum, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Declaration.Enum, ResolutionError] = {
      val casesVal = e0.cases.map {
        case (name, NamedAst.Case(enum, tag, tpe)) =>
          for {
            t <- lookupType(tpe, ns0, prog0)
          } yield name -> ResolvedAst.Case(enum, tag, t)
      }

      for {
        tparams <- seqM(e0.tparams.map(p => Params.resolve(p, ns0, prog0)))
        cases <- seqM(casesVal)
        tpe <- Types.resolve(e0.tpe, ns0, prog0)
      } yield ResolvedAst.Declaration.Enum(e0.doc, e0.sym, tparams, cases.toMap, tpe, e0.loc)
    }

    /**
      * Performs name resolution on the given index `i0` in the given namespace `ns0`.
      */
    def resolve(i0: NamedAst.Declaration.Index, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Declaration.Index, ResolutionError] = {
      lookupTable(i0.qname, ns0, prog0) match {
        case Ok(table) => ResolvedAst.Declaration.Index(table.sym, i0.indexes, i0.loc).toSuccess
        case Err(e) => ??? // TODO
      }
    }

    /**
      * Performs name resolution on the given lattice `l0` in the given namespace `ns0`.
      */
    def resolve(l0: NamedAst.Declaration.BoundedLattice, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Declaration.BoundedLattice, ResolutionError] = {
      for {
        tpe <- lookupType(l0.tpe, ns0, prog0)
        bot <- Expressions.resolve(l0.bot, ns0, prog0)
        top <- Expressions.resolve(l0.top, ns0, prog0)
        leq <- Expressions.resolve(l0.leq, ns0, prog0)
        lub <- Expressions.resolve(l0.lub, ns0, prog0)
        glb <- Expressions.resolve(l0.glb, ns0, prog0)
      } yield ResolvedAst.Declaration.BoundedLattice(tpe, bot, top, leq, lub, glb, ns0, l0.loc)
    }

  }

  object Tables {

    /**
      * Performs name resolution on the given table `t0` in the given namespace `ns0`.
      */
    def resolve(t0: NamedAst.Table, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Table, ResolutionError] = t0 match {
      case NamedAst.Table.Relation(doc, sym, attr, loc) =>
        for {
          as <- seqM(attr.map(a => resolve(a, ns0, prog0)))
        } yield ResolvedAst.Table.Relation(doc, sym, as, loc)

      case NamedAst.Table.Lattice(doc, sym, keys, value, loc) =>
        for {
          ks <- seqM(keys.map(k => resolve(k, ns0, prog0)))
          v <- resolve(value, ns0, prog0)
        } yield ResolvedAst.Table.Lattice(doc, sym, ks, v, loc)
    }

    /**
      * Performs name resolution on the given attribute `a0` in the given namespace `ns0`.
      */
    private def resolve(a0: NamedAst.Attribute, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Attribute, ResolutionError] = {
      for {
        tpe <- lookupType(a0.tpe, ns0, prog0)
      } yield ResolvedAst.Attribute(a0.ident, tpe, a0.loc)
    }

  }

  object Expressions {

    def resolve(exp0: NamedAst.Expression, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Expression, ResolutionError] = {

      def visit(e0: NamedAst.Expression): Validation[ResolvedAst.Expression, ResolutionError] = e0 match {
        case NamedAst.Expression.Wild(tpe, loc) => ResolvedAst.Expression.Wild(tpe, loc).toSuccess

        case NamedAst.Expression.Var(sym, loc) => ResolvedAst.Expression.Var(sym, loc).toSuccess

        case NamedAst.Expression.Ref(ref, tvar, loc) =>
          lookupRef(ref, ns0, prog0) match {
            case Ok(RefTarget.Defn(ns, defn)) =>
              ResolvedAst.Expression.Ref(defn.sym, tvar, loc).toSuccess
            case Ok(RefTarget.Hook(hook)) => ??? // TODO
            case Err(e) => ??? // TODO
          }

        case NamedAst.Expression.Unit(loc) => ResolvedAst.Expression.Unit(loc).toSuccess

        case NamedAst.Expression.True(loc) => ResolvedAst.Expression.True(loc).toSuccess

        case NamedAst.Expression.False(loc) => ResolvedAst.Expression.False(loc).toSuccess

        case NamedAst.Expression.Char(lit, loc) => ResolvedAst.Expression.Char(lit, loc).toSuccess

        case NamedAst.Expression.Float32(lit, loc) => ResolvedAst.Expression.Float32(lit, loc).toSuccess

        case NamedAst.Expression.Float64(lit, loc) => ResolvedAst.Expression.Float64(lit, loc).toSuccess

        case NamedAst.Expression.Int8(lit, loc) => ResolvedAst.Expression.Int8(lit, loc).toSuccess

        case NamedAst.Expression.Int16(lit, loc) => ResolvedAst.Expression.Int16(lit, loc).toSuccess

        case NamedAst.Expression.Int32(lit, loc) => ResolvedAst.Expression.Int32(lit, loc).toSuccess

        case NamedAst.Expression.Int64(lit, loc) => ResolvedAst.Expression.Int64(lit, loc).toSuccess

        case NamedAst.Expression.BigInt(lit, loc) => ResolvedAst.Expression.BigInt(lit, loc).toSuccess

        case NamedAst.Expression.Str(lit, loc) => ResolvedAst.Expression.Str(lit, loc).toSuccess

        case NamedAst.Expression.Apply(lambda, args, tvar, loc) =>
          for {
            e <- visit(lambda)
            es <- seqM(args map visit)
          } yield ResolvedAst.Expression.Apply(e, es, tvar, loc)

        case NamedAst.Expression.Lambda(params, exp, tvar, loc) =>
          for {
            e <- visit(exp)
          } yield ResolvedAst.Expression.Lambda(params, e, tvar, loc)

        case NamedAst.Expression.Unary(op, exp, tvar, loc) =>
          for {
            e <- visit(exp)
          } yield ResolvedAst.Expression.Unary(op, e, tvar, loc)

        case NamedAst.Expression.Binary(op, exp1, exp2, tvar, loc) =>
          for {
            e1 <- visit(exp1)
            e2 <- visit(exp2)
          } yield ResolvedAst.Expression.Binary(op, e1, e2, tvar, loc)

        case NamedAst.Expression.IfThenElse(exp1, exp2, exp3, tvar, loc) =>
          for {
            e1 <- visit(exp1)
            e2 <- visit(exp2)
            e3 <- visit(exp3)
          } yield ResolvedAst.Expression.IfThenElse(e1, e2, e3, tvar, loc)

        case NamedAst.Expression.Let(sym, exp1, exp2, tvar, loc) =>
          for {
            e1 <- visit(exp1)
            e2 <- visit(exp2)
          } yield ResolvedAst.Expression.Let(sym, e1, e2, tvar, loc)

        case NamedAst.Expression.Match(exp, rules, tvar, loc) =>
          val rulesVal = rules map {
            case NamedAst.MatchRule(pat, guard, body) =>
              for {
                p <- Patterns.resolve(pat, ns0, prog0)
                g <- visit(guard)
                b <- visit(body)
              } yield ResolvedAst.MatchRule(p, g, b)
          }

          for {
            e <- visit(exp)
            rs <- seqM(rulesVal)
          } yield ResolvedAst.Expression.Match(e, rs, tvar, loc)

        case NamedAst.Expression.Switch(rules, tvar, loc) =>
          val rulesVal = rules map {
            case (cond, body) => @@(visit(cond), visit(body))
          }
          seqM(rulesVal) map {
            case rs => ResolvedAst.Expression.Switch(rs, tvar, loc)
          }

        case NamedAst.Expression.Tag(enum, tag, exp, tvar, loc) =>
          lookupEnumByTag(enum, tag, ns0, prog0) match {
            case Ok(decl) =>
              for {
                e <- visit(exp)
              } yield ResolvedAst.Expression.Tag(decl.sym, tag.name, e, tvar, loc)
            case Err(e) => ???
          }

        case NamedAst.Expression.Tuple(elms, tvar, loc) =>
          for {
            es <- seqM(elms map visit)
          } yield ResolvedAst.Expression.Tuple(es, tvar, loc)

        case NamedAst.Expression.Existential(fparam, exp, loc) =>
          for {
            fp <- Params.resolve(fparam, ns0, prog0)
            e <- visit(exp)
          } yield ResolvedAst.Expression.Existential(fp, e, loc)

        case NamedAst.Expression.Universal(fparam, exp, loc) =>
          for {
            fp <- Params.resolve(fparam, ns0, prog0)
            e <- visit(exp)
          } yield ResolvedAst.Expression.Universal(fp, e, loc)

        case NamedAst.Expression.Ascribe(exp, tpe, loc) =>
          for {
            e <- visit(exp)
            t <- lookupType(tpe, ns0, prog0)
          } yield ResolvedAst.Expression.Ascribe(e, t, loc)

        case NamedAst.Expression.NativeConstructor(constructor, args, tpe, loc) =>
          for {
            es <- seqM(args map visit)
          } yield ResolvedAst.Expression.NativeConstructor(constructor, es, tpe, loc)

        case NamedAst.Expression.NativeField(field, tpe, loc) => ResolvedAst.Expression.NativeField(field, tpe, loc).toSuccess

        case NamedAst.Expression.NativeMethod(method, args, tpe, loc) =>
          for {
            es <- seqM(args map visit)
          } yield ResolvedAst.Expression.NativeMethod(method, es, tpe, loc)

        case NamedAst.Expression.UserError(tvar, loc) => ResolvedAst.Expression.UserError(tvar, loc).toSuccess
      }

      visit(exp0)
    }

  }

  object Patterns {

    /**
      * Performs name resolution on the given pattern `pat0` in the namespace `ns0`.
      */
    def resolve(pat0: NamedAst.Pattern, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Pattern, ResolutionError] = {

      def visit(p0: NamedAst.Pattern): Validation[ResolvedAst.Pattern, ResolutionError] = p0 match {
        case NamedAst.Pattern.Wild(tvar, loc) => ResolvedAst.Pattern.Wild(tvar, loc).toSuccess

        case NamedAst.Pattern.Var(sym, tvar, loc) => ResolvedAst.Pattern.Var(sym, tvar, loc).toSuccess

        case NamedAst.Pattern.Unit(loc) => ResolvedAst.Pattern.Unit(loc).toSuccess

        case NamedAst.Pattern.True(loc) => ResolvedAst.Pattern.True(loc).toSuccess

        case NamedAst.Pattern.False(loc) => ResolvedAst.Pattern.False(loc).toSuccess

        case NamedAst.Pattern.Char(lit, loc) => ResolvedAst.Pattern.Char(lit, loc).toSuccess

        case NamedAst.Pattern.Float32(lit, loc) => ResolvedAst.Pattern.Float32(lit, loc).toSuccess

        case NamedAst.Pattern.Float64(lit, loc) => ResolvedAst.Pattern.Float64(lit, loc).toSuccess

        case NamedAst.Pattern.Int8(lit, loc) => ResolvedAst.Pattern.Int8(lit, loc).toSuccess

        case NamedAst.Pattern.Int16(lit, loc) => ResolvedAst.Pattern.Int16(lit, loc).toSuccess

        case NamedAst.Pattern.Int32(lit, loc) => ResolvedAst.Pattern.Int32(lit, loc).toSuccess

        case NamedAst.Pattern.Int64(lit, loc) => ResolvedAst.Pattern.Int64(lit, loc).toSuccess

        case NamedAst.Pattern.BigInt(lit, loc) => ResolvedAst.Pattern.BigInt(lit, loc).toSuccess

        case NamedAst.Pattern.Str(lit, loc) => ResolvedAst.Pattern.Str(lit, loc).toSuccess

        case NamedAst.Pattern.Tag(enum, tag, pat, tvar, loc) =>
          lookupEnumByTag(enum, tag, ns0, prog0) match {
            case Ok(decl) =>
              for {
                p <- visit(pat)
              } yield ResolvedAst.Pattern.Tag(decl.sym, tag.name, p, tvar, loc)
            case Err(e) => ???
          }

        case NamedAst.Pattern.Tuple(elms, tvar, loc) =>
          for {
            es <- seqM(elms map visit)
          } yield ResolvedAst.Pattern.Tuple(es, tvar, loc)
      }

      visit(pat0)
    }

  }

  object Predicates {

    object Head {
      /**
        * Performs name resolution on the given head predicate `h0` in the given namespace `ns0`.
        */
      def resolve(h0: NamedAst.Predicate.Head, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Predicate.Head, ResolutionError] = h0 match {
        case NamedAst.Predicate.Head.True(loc) => ResolvedAst.Predicate.Head.True(loc).toSuccess

        case NamedAst.Predicate.Head.False(loc) => ResolvedAst.Predicate.Head.False(loc).toSuccess

        case NamedAst.Predicate.Head.Positive(qname, terms, loc) =>
          for {
            sym <- getTableSym(qname, ns0, prog0)
            ts <- seqM(terms.map(t => Expressions.resolve(t, ns0, prog0)))
          } yield ResolvedAst.Predicate.Head.Positive(sym, ts, loc)

        case NamedAst.Predicate.Head.Negative(qname, terms, loc) =>
          for {
            sym <- getTableSym(qname, ns0, prog0)
            ts <- seqM(terms.map(t => Expressions.resolve(t, ns0, prog0)))
          } yield ResolvedAst.Predicate.Head.Negative(sym, ts, loc)
      }
    }

    object Body {
      /**
        * Performs name resolution on the given body predicate `b0` in the given namespace `ns0`.
        */
      def resolve(b0: NamedAst.Predicate.Body, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Predicate.Body, ResolutionError] = b0 match {
        case NamedAst.Predicate.Body.Positive(qname, terms, loc) =>
          for {
            sym <- getTableSym(qname, ns0, prog0)
            ts <- seqM(terms.map(t => Patterns.resolve(t, ns0, prog0)))
          } yield ResolvedAst.Predicate.Body.Positive(sym, ts, loc)

        case NamedAst.Predicate.Body.Negative(qname, terms, loc) =>
          for {
            sym <- getTableSym(qname, ns0, prog0)
            ts <- seqM(terms.map(t => Patterns.resolve(t, ns0, prog0)))
          } yield ResolvedAst.Predicate.Body.Negative(sym, ts, loc)

        case NamedAst.Predicate.Body.Filter(qname, terms, loc) =>
          lookupRef(qname, ns0, prog0) match {
            case Ok(RefTarget.Defn(ns, defn)) =>
              for {
                ts <- seqM(terms.map(t => Expressions.resolve(t, ns0, prog0)))
              } yield ResolvedAst.Predicate.Body.Filter(defn.sym, ts, loc)
            case Ok(RefTarget.Hook(hook)) => ???
            case Err(e) => ???
          }

        case NamedAst.Predicate.Body.Loop(pat, term, loc) =>
          for {
            p <- Patterns.resolve(pat, ns0, prog0)
            t <- Expressions.resolve(term, ns0, prog0)
          } yield ResolvedAst.Predicate.Body.Loop(p, t, loc)
      }
    }

  }

  object Properties {

    /**
      * Performs name resolution on each of the given `properties` in the given namespace `ns0`.
      */
    def resolve(properties: List[NamedAst.Property], ns0: Name.NName, prog0: NamedAst.Program): Validation[List[ResolvedAst.Property], ResolutionError] = {
      seqM(properties.map(p => resolve(p, ns0, prog0)))
    }

    /**
      * Performs name resolution on the given property `p0` in the given namespace `ns0`.
      */
    def resolve(p0: NamedAst.Property, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Property, ResolutionError] = {
      for {
        e <- Expressions.resolve(p0.exp, ns0, prog0)
      } yield ResolvedAst.Property(p0.law, p0.defn, e, p0.loc)
    }

  }

  object Types {

    /**
      * Performs name resolution on the given type `tpe0` in the given namespace `ns0`.
      */
    // TODO: Get rid of this
    def resolve(tpe0: NamedAst.Type, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.Type, ResolutionError] = {
      /**
        * Local visitor.
        */
      def visit(tpe: NamedAst.Type): Validation[ResolvedAst.Type, ResolutionError] = tpe match {
        case NamedAst.Type.Var(tvar, loc) => ResolvedAst.Type.Var(tvar, loc).toSuccess

        case NamedAst.Type.Unit(loc) => ResolvedAst.Type.Unit(loc).toSuccess

        case NamedAst.Type.Ref(name, loc) => ResolvedAst.Type.Ref(name, loc).toSuccess

        case NamedAst.Type.Enum(name) => ResolvedAst.Type.Enum(name).toSuccess

        case NamedAst.Type.Tuple(elms, loc) =>
          for {
            es <- seqM(elms map visit)
          } yield ResolvedAst.Type.Tuple(es, loc)

        case NamedAst.Type.Arrow(params, ret, loc) =>
          for {
            ps <- seqM(params map visit)
            r <- visit(ret)
          } yield ResolvedAst.Type.Arrow(ps, r, loc)

        case NamedAst.Type.Apply(base, tparams, loc) =>
          for {
            b <- visit(base)
            ps <- seqM(tparams map visit)
          } yield ResolvedAst.Type.Apply(b, ps, loc)
      }

      visit(tpe0)
    }
  }

  object Params {

    /**
      * Performs name resolution on the given constraint parameter `cparam0` in the given namespace `ns0`.
      */
    def resolve(cparam0: NamedAst.ConstraintParam, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.ConstraintParam, ResolutionError] = cparam0 match {
      // TODO: Resolve type.
      case NamedAst.ConstraintParam.HeadParam(sym, tpe, loc) => ResolvedAst.ConstraintParam.HeadParam(sym, tpe, loc).toSuccess
      case NamedAst.ConstraintParam.RuleParam(sym, tpe, loc) => ResolvedAst.ConstraintParam.RuleParam(sym, tpe, loc).toSuccess
    }

    /**
      * Performs name resolution on the given formal parameter `fparam0` in the given namespace `ns0`.
      */
    def resolve(fparam0: NamedAst.FormalParam, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.FormalParam, ResolutionError] = {
      for {
        t <- lookupType(fparam0.tpe, ns0, prog0)
      } yield ResolvedAst.FormalParam(fparam0.sym, t, fparam0.loc)
    }

    /**
      * Performs name resolution on the given type parameter `tparam0` in the given namespace `ns0`.
      */
    def resolve(tparam0: NamedAst.TypeParam, ns0: Name.NName, prog0: NamedAst.Program): Validation[ResolvedAst.TypeParam, ResolutionError] = {
      // TODO: Resolve type.
      ResolvedAst.TypeParam(tparam0.name, tparam0.tpe, tparam0.loc).toSuccess
    }

  }

  /**
    * Returns the symbol of the table with the given qualified name `qname` in the given namespace `ns0`.
    */
  def getTableSym(qname: Name.QName, ns0: Name.NName, prog0: NamedAst.Program): Validation[Symbol.TableSym, ResolutionError] =
    lookupTable(qname, ns0, prog0) match {
      case Ok(table) => table.sym.toSuccess
      case Err(e) => ??? // TODO
    }


  /**
    * The result of a reference lookup.
    */
  sealed trait RefTarget

  object RefTarget {

    case class Defn(ns: Name.NName, defn: NamedAst.Declaration.Definition) extends RefTarget

    case class Hook(hook: Ast.Hook) extends RefTarget

  }

  /**
    * Finds the definition with the qualified name `qname` in the namespace `ns0`.
    */
  def lookupRef(qname: Name.QName, ns0: Name.NName, program: NamedAst.Program): Result[RefTarget, TypeError] = {
    // check whether the reference is fully-qualified.
    if (qname.isUnqualified) {
      // Case 1: Unqualified reference. Lookup both the definition and the hook.
      val defnOpt = program.definitions.getOrElse(ns0, Map.empty).get(qname.ident.name)
      val hookOpt = program.hooks.get(Symbol.mkDefnSym(ns0, qname.ident))

      (defnOpt, hookOpt) match {
        case (Some(defn), None) => Ok(RefTarget.Defn(ns0, defn))
        case (None, Some(hook)) => Ok(RefTarget.Hook(hook))
        case (None, None) =>
          // Try the global namespace.
          program.definitions.getOrElse(Name.RootNS, Map.empty).get(qname.ident.name) match {
            case None => Err(ResolutionError.UndefinedRef(qname, ns0, qname.loc))
            case Some(defn) => Ok(RefTarget.Defn(Name.RootNS, defn))
          }
        case (Some(defn), Some(hook)) => Err(ResolutionError.AmbiguousRef(qname, ns0, qname.loc))
      }
    } else {
      // Case 2: Qualified. Lookup both the definition and the hook.
      val defnOpt = program.definitions.getOrElse(qname.namespace, Map.empty).get(qname.ident.name)
      val hookOpt = program.hooks.get(Symbol.mkDefnSym(qname.namespace, qname.ident))

      (defnOpt, hookOpt) match {
        case (Some(defn), None) => Ok(RefTarget.Defn(qname.namespace, defn))
        case (None, Some(hook)) => Ok(RefTarget.Hook(hook))
        case (None, None) => Err(ResolutionError.UndefinedRef(qname, ns0, qname.loc))
        case (Some(defn), Some(hook)) => Err(ResolutionError.AmbiguousRef(qname, ns0, qname.loc))
      }
    }
  }

  /**
    * Finds the enum definition matching the given qualified name and tag.
    */
  def lookupEnumByTag(qname: Option[Name.QName], tag: Name.Ident, ns: Name.NName, prog0: NamedAst.Program): Result[NamedAst.Declaration.Enum, TypeError] = {
    /*
     * Lookup the tag name in all enums across all namespaces.
     */
    val globalMatches = mutable.Set.empty[NamedAst.Declaration.Enum]
    for ((_, decls) <- prog0.enums) {
      for ((enumName, decl) <- decls) {
        for ((tagName, caze) <- decl.cases) {
          if (tag.name == tagName) {
            globalMatches += decl
          }
        }
      }
    }

    // Case 1: Exact match found. Simply return it.
    if (globalMatches.size == 1) {
      return Ok(globalMatches.head)
    }

    // Case 2: No or multiple matches found.
    // Lookup the tag in either the fully qualified namespace or the current namespace.
    val namespace = if (qname.exists(_.isQualified)) qname.get.namespace else ns

    /*
     * Lookup the tag name in all enums in the current namespace.
     */
    val namespaceMatches = mutable.Set.empty[NamedAst.Declaration.Enum]
    for ((enumName, decl) <- prog0.enums.getOrElse(namespace, Map.empty[String, NamedAst.Declaration.Enum])) {
      for ((tagName, caze) <- decl.cases) {
        if (tag.name == tagName) {
          namespaceMatches += decl
        }
      }
    }

    // Case 2.1: Exact match found in namespace. Simply return it.
    if (namespaceMatches.size == 1) {
      return Ok(namespaceMatches.head)
    }

    // Case 2.2: No matches found in namespace.
    if (namespaceMatches.isEmpty) {
      return Err(ResolutionError.UndefinedTag(tag.name, ns, tag.loc))
    }

    // Case 2.3: Multiple matches found in namespace and no enum name.
    if (qname.isEmpty) {
      val locs = namespaceMatches.map(_.sym.loc).toList.sorted
      return Err(ResolutionError.AmbiguousTag(tag.name, ns, locs, tag.loc))
    }

    // Case 2.4: Multiple matches found in namespace and an enum name is available.
    val filteredMatches = namespaceMatches.filter(_.sym.name == qname.get.ident.name)
    if (filteredMatches.size == 1) {
      return Ok(filteredMatches.head)
    }

    Err(ResolutionError.UndefinedTag(tag.name, ns, tag.loc))
  }


  /**
    * Finds the table of the given `qname` in the namespace `ns`.
    *
    * Returns [[Err]] of [[ResolutionError.UndefinedTable]] if the table does not exist.
    */
  def lookupTable(qname: Name.QName, ns: Name.NName, program: NamedAst.Program): Result[NamedAst.Table, TypeError] = {
    if (qname.isUnqualified) {
      // Lookup in the current namespace.
      val tables = program.tables.getOrElse(ns, Map.empty)
      tables.get(qname.ident.name) match {
        case None => Err(ResolutionError.UndefinedTable(qname, ns, qname.loc))
        case Some(table) => Ok(table)
      }
    } else {
      // Lookup in the qualified namespace.
      val tables = program.tables.getOrElse(qname.namespace, Map.empty)
      tables.get(qname.ident.name) match {
        case None => Err(ResolutionError.UndefinedTable(qname, qname.namespace, qname.loc))
        case Some(table) => Ok(table)
      }
    }
  }

  /**
    * Resolves the given type `tpe0` in the given namespace `ns0`.
    */
  def lookupType(tpe0: NamedAst.Type, ns0: Name.NName, program: NamedAst.Program): Validation[Type, ResolutionError] = tpe0 match {
    case NamedAst.Type.Var(tvar, loc) => tvar.toSuccess
    case NamedAst.Type.Unit(loc) => Type.Unit.toSuccess
    case NamedAst.Type.Ref(qname, loc) if qname.isUnqualified => qname.ident.name match {
      // Basic Types
      case "Unit" => Type.Unit.toSuccess
      case "Bool" => Type.Bool.toSuccess
      case "Char" => Type.Char.toSuccess
      case "Float" => Type.Float64.toSuccess
      case "Float32" => Type.Float32.toSuccess
      case "Float64" => Type.Float64.toSuccess
      case "Int" => Type.Int32.toSuccess
      case "Int8" => Type.Int8.toSuccess
      case "Int16" => Type.Int16.toSuccess
      case "Int32" => Type.Int32.toSuccess
      case "Int64" => Type.Int64.toSuccess
      case "BigInt" => Type.BigInt.toSuccess
      case "Str" => Type.Str.toSuccess
      case "Native" => Type.Native.toSuccess

      // Enum Types.
      case typeName =>
        // Lookup the enum in the current namespace.
        // If the namespace doesn't even exist, just use an empty map.
        val namespaceDecls = program.enums.getOrElse(ns0, Map.empty)
        namespaceDecls.get(typeName) match {
          case None =>
            // The enum was not found in the current namespace. Try the root namespace.
            val rootDecls = program.enums.getOrElse(Name.RootNS, Map.empty)
            rootDecls.get(typeName) match {
              case None => ResolutionError.UndefinedType(qname, ns0, loc).toFailure
              case Some(enum) => Type.Enum(enum.sym, Kind.Star /* TODO: Kind */).toSuccess
            }
          case Some(enum) => Type.Enum(enum.sym, Kind.Star /* TODO: Kind */).toSuccess
        }
    }
    case NamedAst.Type.Ref(qname, loc) if qname.isQualified =>
      // Lookup the enum using the namespace.
      val decls = program.enums.getOrElse(qname.namespace, Map.empty)
      decls.get(qname.ident.name) match {
        case None => ResolutionError.UndefinedType(qname, ns0, loc).toFailure
        case Some(enum) => Type.Enum(enum.sym, Kind.Star /* TODO: Kind */).toSuccess
      }
    case NamedAst.Type.Enum(sym) =>
      Type.Enum(sym, Kind.Star /* TODO: Kind */).toSuccess
    case NamedAst.Type.Tuple(elms0, loc) =>
      for (
        elms <- seqM(elms0.map(tpe => lookupType(tpe, ns0, program)))
      ) yield Type.mkFTuple(elms)
    case NamedAst.Type.Arrow(tparams0, tresult0, loc) =>
      for (
        tparams <- seqM(tparams0.map(tpe => lookupType(tpe, ns0, program)));
        tresult <- lookupType(tresult0, ns0, program)
      ) yield Type.mkArrow(tparams, tresult)
    case NamedAst.Type.Apply(base0, tparams0, loc) =>
      for (
        baseType <- lookupType(base0, ns0, program);
        argTypes <- seqM(tparams0.map(tpe => lookupType(tpe, ns0, program)))
      ) yield Type.Apply(baseType, argTypes)

  }

  /**
    * Resolves the given type `tpe0` in the given namespace `ns0`.
    */
  def lookupTypes(tpes0: List[NamedAst.Type], ns0: Name.NName, program: NamedAst.Program): Validation[List[Type], ResolutionError] = {
    seqM(tpes0.map(tpe => lookupType(tpe, ns0, program)))
  }

  /**
    * Resolves the given scheme `sc0` in the given namespace `ns0`.
    */
  def lookupScheme(sc0: NamedAst.Scheme, ns0: Name.NName, program: NamedAst.Program): Validation[Scheme, ResolutionError] = {
    lookupType(sc0.base, ns0, program) map {
      case base => Scheme(sc0.quantifiers, base)
    }
  }

}
