package zio.asyncawait.core

import scala.quoted._
import zio.Task
import zio.asyncawait.core.metaprog.Extractors._
import zio.asyncawait.core.metaprog._
import zio.asyncawait._
import zio.asyncawait.core.util.Format
import zio.ZIO
import scala.collection.mutable
import zio.Chunk

class Transformer(using transformerQuotes: Quotes) {
  import quotes.reflect._

  private def replaceSymbolIn(in: Term)(oldSymbol: Symbol, newSymbolTerm: Term) =
    BlockN(List(
      ValDef(oldSymbol, Some(newSymbolTerm)),
      in
    ))

  def useNewSymbolIn(using Quotes)(tpe: quotes.reflect.TypeRepr)(useSymbol: quotes.reflect.Term => quotes.reflect.Term) = {
    import quotes.reflect._
    // TODO Try to write this using ValDef.let(...). Might be more efficient
    val (symbol, body) =
      tpe.asType match
        case '[t] =>
          // TODO get rid of underlyingArgument. Should only need one top-level Uninline
          '{ val m: t = ???; ${useSymbol(('m).asTerm).asExpr} }.asTerm.underlyingArgument match
            case Block(
              (valdef @ ValDef(_, _, _)) :: Nil,
              body
            ) =>
              (valdef.symbol, body)

    println(s"============  Making New Symbol For: ${symbol} -> ${Printer.TreeShortCode.show(body)}")
    (symbol, body)
  }

  object Transform {
    def apply(expr: Expr[?]): Expr[ZIO[Any, Throwable, ?]] =
      unapply(expr.asTerm.underlyingArgument.asExpr)
        .getOrElse('{ ZIO.succeed($expr) })

    // TODO really use underlyingArgument????
    def unapply(expr: Expr[?]): Option[Expr[ZIO[Any, Throwable, ?]]] = {
      println("================== UNAPPLY ==================")
      val ret = expr match {
        case Unseal(PureTree(tree)) =>
          println(s"============  Tree is Pure!: ${tree.show}")
          None

        case Unseal(block @ Block(parts, lastPart)) if (parts.nonEmpty) =>
          println(s"============  Block: ${parts.map(part => Format.Tree(part)).mkString("List(\n", ",\n", ")\n")} ==== ${Format.Tree(lastPart)}")
          TransformBlock.unapply(block)

        case Unseal(Match(m @ Seal(Transform(monad)), caseDefs)) =>
          println(s"============  Body Has Monad =======\n${Printer.TreeShortCode.show(m)}")
          println(s"====== Transformed:\n" + Format.Expr(monad))
          println(s"====== Monad Tpe:\n" + Format.TypeRepr(monad.asTerm.tpe))
          println(s"====== Match Tpe:\n" + Format.TypeRepr(m.tpe))

          // Since in Scala 3 we cannot just create a arbitrary symbol and pass it around.
          // (See https://github.com/lampepfl/dotty/blob/33818506801c80c8c73649fdaab3782c052580c6/library/src/scala/quoted/Quotes.scala#L3675)
          // In order to be able to have a valdef-symbol to manipulate, we need to create the actual valdef
          // Therefore we need to create
          // a synthetic val-def for the symbol with which to substitute this expression.
          // For example if we want to substitute something like this:
          //   unlift(ZIO.attempt(stuff)) match { case ...can-use-m.... }
          // We need to do something like this:
          //   ZIO.attempt(stuff).map(m => match { case ...can-use-m.... })
          // However, in order to be able to get the `m => ...`
          // We first need to create a fake val-def + right-hand-side that looks like:
          //   '{ val m:StuffType = ???; ...can-use-m.... }
          // So the Nest-call can actually change it to:
          //   ZIO.attempt(stuff).map(m => match { case ...can-use-m.... })
          val (oldSymbol, body) =
            useNewSymbolIn(m.tpe)(sym => Match(sym, caseDefs))

          val s = oldSymbol
          println(s"========= SYMBOL INFO ${s.owner}/${Symbol.spliceOwner}, ${s.name}, ${s.flags.show}, ${s.privateWithin}")

          val out = Nest(monad, Nest.NestType.ValDef(oldSymbol), body.asExpr)
          Some(out)

          // val sym = Symbol.newVal(Symbol.spliceOwner, "m", m.tpe, Flags.EmptyFlags, Symbol.noSymbol)
          // val body = Match(Ident(sym.termRef), caseDefs)
          // val out = Nest(monad, Nest.NestType.ValDef(sym), body.asExpr)
          // println(s"============  Body Has Monad RETURN =======\n${Printer.TreeShortCode.show(out.asTerm)}")
          // Some(out)

        case Unseal(m @ Match(value, TransformCases(cases))) =>
          println(s"=============== Transform Inner Cases")
          Some(Match(value, cases).asExprOf[ZIO[Any, Throwable, ?]])

        case '{ await[t]($task) } =>
          println(s"=============== Unlift: ${task.show}")
          Some(task)

        case Unseal(Typed(tree, _)) =>
          println(s"=============== Untype: ${tree.show}")
          unapply(tree.asExpr)

        // TODO MAJOR Only support parallel awaits for strict-set of known constructs
        //      Examine set of cases where this occurs. Then as a final case
        //      Check that there are awaits in any other kind of construct
        //      and throw an unsupproted-construct exception.
        case tree =>
          println(s"=============== Other: ${Format(Printer.TreeShortCode.show(tree.asTerm))}")
          val unlifts = mutable.ArrayBuffer.empty[(Term, Symbol, TypeRepr)]
          val newTree: Term =
            Trees.Transform(tree.asTerm, Symbol.spliceOwner) {
              case Seal('{ await[t]($task) }) =>
                val tpe =
                  task.asTerm.tpe.asType match
                    case '[ZIO[x, y, t]] => TypeRepr.of[t]
                // (unlift(A), unlift(B))
                // Would yield
                // (newSymA:Symbol, Ident(newSymA)), (newSymA:Symbol, Ident(newSymB))
                // The idents would be swapped back into the expressions and A, B
                // would be stored in the unlifts array
                val (sym, symId) =
                  useNewSymbolIn(tpe)(symId => symId)
                unlifts += ((task.asTerm, sym, tpe))
                symId
            }

          unlifts.toList match {
            case List() =>
              println("=========== No Unlifts ==========")
              None
            case List((monad, name, tpe)) =>
              val out =
              tpe.asType match
                case '[t] =>
                  newTree.tpe.asType match
                    case '[r] =>
                      Some('{
                        ${monad.asExprOf[ZIO[Any, Throwable, t]]}.map[r](sm =>
                            ${replaceSymbolIn(newTree)(name, ('sm).asTerm).asExpr}.asInstanceOf[r]
                          ).asInstanceOf[ZIO[Any, Throwable, r]]
                        }
                      )
              println("=========== Single unlift: ==========\n" + Format.Expr(out.get))
              out
            case unlifts =>
              val (terms, names, types) = unlifts.unzip3
              val termsExpr = Expr.ofList(terms.map(_.asExprOf[ZIO[Any, Throwable, ?]]))
              val collect = '{ ZIO.collectAll(Chunk.from($termsExpr)) }
              def makeVariables(iterator: Expr[Iterator[?]]) =
                unlifts.map((monad, symbol, tpe) =>
                    tpe.asType match {
                      case '[t] =>
                        ValDef(symbol, Some('{ $iterator.next().asInstanceOf[t] }.asTerm))
                    }
                )

              val totalType =
                terms.drop(1).foldLeft(terms.head.tpe)((tpe, additionalTerm) =>
                  tpe.asType match
                    case '[ZIO[x, y, a]] =>
                      additionalTerm.tpe.asType match
                        case '[ZIO[x1, y1, b]] =>
                          TypeRepr.of[a with b]
                )

              println(s"============ Computed Total Type: ${Format.TypeRepr(totalType)}")

              val output =
                totalType.asType match
                  case '[t] =>
                    '{
                      $collect.map(terms => {
                        val iter = terms.iterator
                        ${ Block(makeVariables('iter), newTree).asExpr }
                      }).asInstanceOf[ZIO[Any, Throwable, t]]
                    }

              val out = Some(output)
              println(s"============ Computed Output: ${Format.TypeRepr(output.asTerm.tpe)}")
              println("=========== Multiple unlift: ==========\n" + Format.Expr(out.get))
              out
          }
      }
      println("================== DONE UNAPPLY ==================")
      ret
    }
  }

  private object PureTree:
    def unapply(tree: Tree) =
      Trees.exists(tree, Symbol.spliceOwner) {
        case Seal('{ await[t]($v) }) => true
      } match {
        case true => None
        case false => Some(tree)
      }

  private object Nest {
    enum NestType:
      case ValDef(symbol: Symbol)
      case Wildcard

    /**
      * This will actually take some block of code (that is either in a ZIO or just 'pure' code)
      * and will nest it into the previously-sequenced ZIO. If it is ZIO code, it will
      * flatMap from the previously-sequenced ZIO, otherwise it map.
      */
    def apply(monad: Expr[ZIO[Any, Throwable, ?]], nestType: NestType, bodyRaw: Expr[_]): Expr[ZIO[Any, Throwable, ?]] = {
      def symbolType =
        nestType match
          case NestType.ValDef(oldSymbol) =>
            oldSymbol.termRef.widenTermRefByName.asType
          case _ =>
            monad.asTerm.tpe.asType match
              case '[ZIO[Any, Throwable, t]] => Type.of[t]

      // def decideMonadType[MonadType: Type] =
      //   val monadType = Type.of[MonadType]
      //   monadType match
      //     case '[Any] =>
      //       oldSymbolType match
      //         case Some(value) => (value.asType, true)
      //         case None => (monadType, false)
      //     case _ =>
      //       (monadType, false)

      def replaceSymbolInBody(body: Term)(newSymbolTerm: Term) =
        nestType match
          case NestType.ValDef(oldSymbol) =>
            /**
             * In a case where we have:
             *  val a = unlift(foobar)
             *  otherStuff
             *
             * We can either lift that into:
             *  unlift(foobar).flatMap { v => (otherStuff /*with replaced a -> v*/) }
             *
             * Or we can just do
             *  unlift(foobar).flatMap { v => { val a = v; otherStuff /* with the original a variable*/ } }
             *
             * I think the 2nd variant more performant but keeping 1st one (Trees.replaceIdent(...)) around for now.
             */
            //Trees.replaceIdent(using transformerQuotes)(body)(oldSymbol, newSymbolTerm.symbol)

            val out = replaceSymbolIn(body)(oldSymbol, newSymbolTerm)
            println(s"============+ Creating $oldSymbol:${Printer.TypeReprShortCode.show(oldSymbol.termRef.widen)} -> ${newSymbolTerm.show}:${Printer.TypeReprShortCode.show(newSymbolTerm.tpe.widen)} replacement let:\n${Format(Printer.TreeShortCode.show(out))}")
            out

          case NestType.Wildcard =>
            body

      bodyRaw match {
        // q"${Resolve.flatMap(monad.pos, monad)}(${toVal(name)} => $body)"
        case Transform(body) =>
          println(s"=================== Flat Mapping: ${Format(Printer.TreeShortCode.show(body.asTerm))}")
          println(s"Monad Type: ${monad.asTerm.tpe.show}")
          symbolType match
            case '[t] =>
              '{ $monad.asInstanceOf[ZIO[Any, Throwable, t]].flatMap((v: t) =>
                ${replaceSymbolInBody(body.asTerm)(('v).asTerm).asExprOf[ZIO[Any, Throwable, ?]]}
              ) }

        // q"${Resolve.map(monad.pos, monad)}(${toVal(name)} => $body)"
        case body            =>
          println(s"=================== Mapping: ${Format(Printer.TreeShortCode.show(body.asTerm))}")
          symbolType match
            case '[t] =>
              '{ $monad.asInstanceOf[ZIO[Any, Throwable, t]].map((v: t) =>
                ${replaceSymbolInBody(body.asTerm)(('v).asTerm).asExpr}
              ) }
      }
    }
  }

  /**
    * Transform a sequence of steps
    * a; b = unlift(zio); c
    * Into a.flatMap()
    */
  private object TransformBlock {
    def unapply(block: Block): Option[Expr[ZIO[Any, Throwable, ?]]] =
      val Block(head, tail) = block
      val parts = head :+ tail
      parts match {
        // This is the most important use-case of the monadless system.
        // Change this:
        //   val x = unlift(stuff)
        //   stuff-that-uses-x
        // Into this:
        //   stuff.flatMap(x => ...)
        //   stuff-that-uses-x
        //
        // This basically does that with some additional details
        // (e.g. it can actually be stuff.flatMap(v => val x = v; stuff-that-uses-x))
        case ValDefStatement(symbol , Seal(Transform(monad))) :: tail =>
          println(s"============= Block - Val Def: ${Printer.TreeShortCode.show(monad.asTerm)}")
          val nest = Nest(monad, Nest.NestType.ValDef(symbol), BlockN(tail).asExpr)
          Some(nest.asExprOf[ZIO[Any, Throwable, ?]])

        // TODO Validate this?
        //case MatchValDef(name, body) :: tail =>
        //  report.throwError(s"===== validef match bad body: ${body.show}")

        // other statements possible including ClassDef etc... should look into that

        case MatchTerm(Seal(Transform(monad))) :: tail =>
          tail match {
            // In this case where is one statement in the block which my definition
            // needs to have the same type as the output: e.g.
            //   val v: T = { unlift(doSomething:ZIO[_, _, T]) }
            // since we've pulled out the `doSomething` inside the signature
            // will be ZIO[_, _, T] instead of T.
            case Nil =>
              println(s"============= Block - With zero terms: ${monad.show}")
              Some(monad.asExprOf[ZIO[Any, Throwable, ?]])
            case list =>
              // In this case there are multiple instructions inside the seauence e.g:
              //   val v: T = { unlift(x), y /*pure*/, unlift(z:ZIO[_, _, T]) }
              // We recurse by flatMapping x, y, and unlift... but eventually the type
              // also has to be ZIO[_, _, T] since we are essentially doing:
              // x.flatMap(.. -> {y; z: ZIO[_, _, T]}). Of course the value will be ZIO[_, _, T]
              // since the last value of a nested flatMap chain is just the last instruction
              // in the nested sequence.
              println(s"============= Block - With multiple terms: ${monad.show}, ${list.map(_.show)}")
              val nest = Nest(monad, Nest.NestType.Wildcard, BlockN(tail).asExpr)
              Some(nest.asExprOf[ZIO[Any, Throwable, ?]])
          }

        // This is the recursive case of TransformBlock, it will work across multiple things
        // between blocks due to the recursion e.g:
        //   val blah = new Blah(2) // 1st part, will recurse 1st time (here)
        //   import blah._          // 2nd part, will recurse 2nd time (here)
        //   val b = unlift(ZIO.succeed(value).asInstanceOf[Task[Int]]) // Then will match valdef case
        case head :: BlockN(TransformBlock(parts)) =>
          Some(BlockN(List(head, parts.asTerm)).asExprOf[ZIO[Any, Throwable, ?]])

        // Throw error if it is a non-term?
        case other =>
          // TODO fail if there is an unlift here! definitely a problem
          println(s"============= NO BLOCK MATCHED: ${other.map(_.show)}")
          None
      }
  }

  private object TransformDefs {

    def apply(stmtRaw: Tree): Tree = {
      var unlifted = mutable.Map[Symbol, Ref]()

      // TODO Test with mutually recursive methods
      object UnliftDefs {
        def apply(term: Tree): Tree =
          Trees.TransformTree(term, Symbol.spliceOwner) {

            // Note: If we have transformed this function, make sure NOT to touch it
            // Scala seems to give `key not found: method` errors when a any method is just deleted so we nest the method inside
            // the function block so at least it will be there.
            case defdef @ DefDef(name, paramss, tpt, Some(rhs)) if (!unlifted.contains(defdef.symbol)) =>
              val out = rhs match {
                case PureTree(body) => defdef
                case _ =>
                  // Make sure to widen the type of the def parameter or it will just be a.type
                  tpt.tpe.widen.asType match
                    case '[t] =>
                      val outputType = TypeRepr.of[ZIO[Any, Throwable, t]]
                      val newSymbol = DefDefCopy.computeNewSymbol(defdef, outputType)
                      unlifted += ((defdef.symbol, Ref(newSymbol)))
                      println(s"========== Copying Function: ${Format.Tree(defdef)}")
                      DefDefCopy.of(newSymbol, defdef, outputType)((terms, body) => {
                        val newBodyInner = Transform(UnliftDefs(body).asExpr).asTerm
                        // Not technically needed but useful to have if we want thte body to return a quote
                        given Quotes = newSymbol.asQuotes
                        // Unless you change the owner there will be an error that the original DefDef symbol is not found
                        // it will look something like: `key not found: method <original-method-name>`
                        newBodyInner.changeOwner(newSymbol)
                      })

              }
              println(s"========= UNLIFT DEF - DefDef TreeTransform =======\n${Format.Tree(defdef)}\n=========INTO:\n${Format.Tree(out)}")
              out

            case tree @ Seal('{ await[t]($task) }) =>
              tree

            // Is it a function call i.e. x.y
            // (several other forms of function-call can happen, need to match in a more general way)
            // TODO Make sure to check that class definition methods (e.g. Apply(Select(Ident, method), ...) method
            // invocations are not allowed)
            case FunctionApplyMatroshka(functionCall, functionIdent) if (unlifted.contains(functionIdent.symbol)) =>
              term match
                case Transform(_) =>
                  report.errorAndAbort("Can't unlift parameters of a method with unlifted body.")
                case term =>
                  // Replace the symbol in the function call
                  val newTermRef = unlifted(functionIdent.symbol)
                  val newFunctionCall = Trees.replaceIdent(functionCall)(functionIdent.symbol, newTermRef)
                  functionCall.tpe.widen.asType match
                    case '[t] =>
                      '{ await[t](${newFunctionCall.asExpr}.asInstanceOf[ZIO[Any, Throwable, t]]) }.asTerm.underlyingArgument

            // TODO Invalid, make this an error case, needs have an right-hand-sde
            //case DefDef(sym, paramss, tpt, None) =>
          }
        end apply
      }

      UnliftDefs(stmtRaw) match {
        // Is the statement the same thing as the old one? If so leave it
        case `stmtRaw` => stmtRaw
        // Otherwise (i.e. if the statement is actually different) then return it
        case newStmt => UnliftDefs(newStmt)
      }
    }
  }


  private object TransformCases {
    private sealed trait AppliedTree { def tree: CaseDef }
    private case object AppliedTree {
      case class HasTransform(tree: CaseDef) extends AppliedTree
      case class NoTransform(tree: CaseDef) extends AppliedTree
    }

    def apply(cases: List[CaseDef]): List[CaseDef] =
      applyMark(cases).map(_.tree)

    private def applyMark(cases: List[CaseDef]) =
      cases.map {
        case CaseDef(pattern, cond, Seal(Transform(body))) => AppliedTree.HasTransform(CaseDef(pattern, cond, body.asTerm))
        case CaseDef(pattern, cond, body) => AppliedTree.NoTransform(CaseDef(pattern, cond, '{ ZIO.attempt(${body.asExpr}) }.asTerm))
      }

    def unapply(cases: List[CaseDef]) = {
        // If at least one of the match-cases need to be transformed, transform all of them
        val mappedCases = applyMark(cases)
        if (mappedCases.exists(_.isInstanceOf[AppliedTree.HasTransform]))
          Some(mappedCases.map(_.tree))
        else
          None
      }
  }

  private object BlockN {
    def unapply(trees: List[Statement]) =
      trees match {
        case Nil => None
        case MatchTerm(head) :: Nil =>
          Some(Block(Nil, head))
        case list if (MatchTerm.unapply(list.last).isDefined) =>
          Some(Block(list.dropRight(1), MatchTerm.unapply(list.last).get))
        case _ =>
          report.errorAndAbort(s"Last element in the instruction group is not a block. ${trees.map(_.show)}")
      }

    def apply(trees: List[Statement]): Block =
      BlockN.unapply(trees) match {
        case Some(value) => value
        case None => report.errorAndAbort(s"Invalid trees list: ${trees.map(_.show)}")
      }
  }

  def apply[T: Type](value: Expr[T])(using Quotes): Expr[ZIO[Any, Throwable, T]] = {
    val transformed =
      TransformDefs(value.asTerm.underlyingArgument) match {
        case PureTree(tree) if (tree.isExpr) =>
          println(s"========= Result of Top-Level TransformDefs was a pure expression:\n${Printer.TreeShortCode.show(tree)}")
          '{ ZIO.succeed(${tree.asExpr}) }
        case tree if (tree.isExpr) =>
          println(s"========= Continuing to transform after top-level:\n${Printer.TreeShortCode.show(tree)}")
          Transform(tree.asExpr)
        case other =>
          report.errorAndAbort(s"Could not translate the following tree because it was not an expression:\n${Printer.TreeShortCode.show(other)}")
      }
    '{ ${transformed}.asInstanceOf[ZIO[Any, Throwable, T]] }
  }
}