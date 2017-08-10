/*
 * Copyright 2015-2016 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix

import ca.uwaterloo.flix.api.{Flix, Enum, Tuple, Unit => UnitClass}
import ca.uwaterloo.flix.runtime.{Model, Value}
import ca.uwaterloo.flix.util._
import org.scalatest.FunSuite

class TestExamples extends FunSuite {

  private class Tester(dumpBytecode: Boolean = false) {

    private val flix = createFlix(codegen = true)
    private var compiled: Model = null

    def getBoxedIfNecessary(res: AnyRef): AnyRef = res match {
      case r: Enum => {
        new Value.Tag(r.getTag, getBoxedIfNecessary(r.getBoxedValue()))
      }
      case r: Tuple => {
        r.getBoxedValue().map(getBoxedIfNecessary)
      }
      case r: UnitClass => Value.Unit
      case x => x
    }

    private def createFlix(codegen: Boolean = false) = {
      val options = Options.DefaultTest.copy(core = false, evaluation = if (codegen) Evaluation.Compiled else Evaluation.Interpreted)
      new Flix().setOptions(options)
    }

    def addPath(path: String): Tester = {
      flix.addPath(path)
      this
    }

    def addStr(str: String): Tester = {
      flix.addStr(str)
      this
    }

    def run(): Tester = {
      compiled = flix.solve().get
      this
    }

    def checkValue(expected: AnyRef, latticeName: String, key: List[AnyRef]): Unit = {
      withClue(s"compiled value $latticeName($key):") {
        val lattice = compiled.getLattice(latticeName).toMap
        assertResult(expected)(getBoxedIfNecessary(lattice(key)))
      }
    }

    def checkNone(latticeName: String, key: List[AnyRef]): Unit = {
      withClue(s"compiled value $latticeName($key):") {
        val lattice = compiled.getLattice(latticeName).toMap
        assertResult(None)(lattice.get(key))
      }
    }

    def checkSuccess(): Unit = {
      assert(flix.solve().isSuccess)
    }

  }

  /////////////////////////////////////////////////////////////////////////////
  // Domains                                                                 //
  /////////////////////////////////////////////////////////////////////////////

  test("Belnap.flix") {
    val input =
      """namespace Belnap {
        |    let Belnap<> = (Belnap.Bot, Belnap.Top, leq, lub, glb)
        |    lat A(k: Int, v: Belnap)
        |
        |    A(1, Belnap.True).
        |    A(2, Belnap.False).
        |
        |    A(3, Belnap.True).
        |    A(3, Belnap.False).
        |
        |    A(4, x) :- A(1, x), A(2, x).
        |
        |    A(5, not(Belnap.False)).
        |
        |    A(6, and(Belnap.True, Belnap.False)).
        |
        |    A(7, or(Belnap.True, Belnap.False)).
        |
        |    A(8, xor(Belnap.True, Belnap.False)).
        |}
      """.stripMargin

    val t = new Tester()
      .addPath("./examples/domains/Belnap.flix")
      .addStr(input)
      .run()

  }

  test("Constant.flix") {
    val input =
      """namespace Domain/Constant {
        |    let Constant<> = (Constant.Bot, Constant.Top, leq, lub, glb)
        |    lat A(k: Int, v: Constant)
        |
        |    A(0, Cst(0)).
        |    A(1, Cst(1)).
        |    A(2, Cst(2)).
        |
        |    A(3, x) :- A(0, x).
        |    A(3, x) :- A(1, x).
        |    A(3, x) :- A(2, x).
        |
        |    A(4, x) :- A(0, x), A(1, x), A(2, x).
        |
        |    A(5, plus(x, y))  :- A(0, x), A(2, y).
        |    A(6, times(x, y)) :- A(1, x), A(2, y).
        |}
      """.stripMargin

    val t = new Tester()
      .addPath("./examples/domains/Belnap.flix")
      .addPath("./examples/domains/Constant.flix")
      .addStr(input)
      .run()
  }

  test("ConstantSign.flix") {
    val input =
      """namespace Domain/ConstantSign {
        |    let ConstantSign<> = (Bot, Top, leq, lub, glb)
        |    lat A(k: Int, v: ConstantSign)
        |
        |    A(1, Cst(-1ii)).
        |    A(2, Cst(0ii)).
        |    A(3, Cst(1ii)).
        |
        |    A(4, x) :- A(1, x). // 4 -> top
        |    A(4, x) :- A(2, x). // 4 -> top
        |    A(4, x) :- A(3, x). // 4 -> top
        |
        |    A(5, x) :- A(2, x). // 5 -> pos
        |    A(5, x) :- A(3, x). // 5 -> pos
        |
        |    A(6, x) :- A(1, x), A(2, x). // 6 -> bot
        |    A(7, x) :- A(2, x), A(3, x). // 7 -> bot
        |
        |    A(8, x) :- A(4, x), A(5, x). // 8 -> pos
        |
        |    A(9, times(x, y)) :- A(1, x), A(1, y). // 9 -> 1
        |}
      """.stripMargin

    val t = new Tester()
      .addPath("./examples/domains/Belnap.flix")
      .addPath("./examples/domains/ConstantSign.flix")
      .addStr(input)
      .run()

    // TODO: Check values.
  }

  test("ConstantParity.flix") {
    val input = ""

    val t = new Tester()
      .addPath("./examples/domains/Belnap.flix")
      .addPath("./examples/domains/ConstantParity.flix")
      .addStr(input)
      .run()

    // TODO: Exercise lattice.
  }

  test("Mod3.flix") {
    val input = ""

    val t = new Tester()
      .addPath("./examples/domains/Belnap.flix")
      .addPath("./examples/domains/Mod3.flix")
      .addStr(input)
      .run()

    // TODO: Exercise lattice.
  }

  test("Parity.flix") {
    val input =
      """namespace Domain/Parity {
        |    let Parity<> = (Parity.Bot, Parity.Top, leq, lub, glb)
        |    lat A(k: Int, v: Parity)
        |
        |    A(1, Odd).
        |    A(2, Even).
        |
        |    A(3, Odd).
        |    A(3, Even).
        |
        |    A(4, x) :- A(1, x), A(2, x).
        |
        |    A(5, plus(Odd, Even)).
        |
        |    A(6, plus(Odd, Odd)).
        |
        |    A(7, times(Odd, Even)).
        |
        |    A(8, times(Odd, Odd)).
        |}
      """.stripMargin

    val t = new Tester()
      .addPath("./examples/domains/Belnap.flix")
      .addPath("./examples/domains/Parity.flix")
      .addStr(input)
      .run()

  }

  test("ParitySign.flix") {
    val input = ""

    val t = new Tester()
      .addPath("./examples/domains/Belnap.flix")
      .addPath("./examples/domains/ParitySign.flix")
      .addStr(input)
      .run()

    // TODO: Exercise lattice.
  }

  test("PrefixSuffix.flix") {
    val input = ""

    val t = new Tester()
      .addPath("./examples/domains/Belnap.flix")
      .addPath("./examples/domains/PrefixSuffix.flix")
      .addStr(input)
      .run()

    // TODO: Exercise lattice.
  }

  test("Sign.flix") {
    val input = ""

    val t = new Tester()
      .addPath("./examples/domains/Belnap.flix")
      .addPath("./examples/domains/Sign.flix")
      .addStr(input)
      .run()

    // TODO: Exercise lattice.
  }

  test("StrictSign.flix") {
    val input =
      """namespace Domain/StrictSign {
        |    let Sign<> = (Sign.Bot, Sign.Top, leq, lub, glb)
        |    lat A(k: Int, v: Sign)
        |
        |    A(1, Neg).
        |    A(2, Zer).
        |    A(3, Pos).
        |
        |    A(4, Neg).
        |    A(4, Zer).
        |    A(4, Pos).
        |
        |    A(5, x) :- A(1, x), A(2, x), A(3, x).
        |
        |    A(6, plus(Zer, Pos)).
        |    A(7, plus(Neg, Pos)).
        |
        |    A(8, times(Zer, Pos)).
        |    A(9, times(Neg, Neg)).
        |}
      """.stripMargin

    val t = new Tester()
      .addPath("./examples/domains/Belnap.flix")
      .addPath("./examples/domains/StrictSign.flix")
      .addStr(input)
      .run()
  }

  test("IFDS.flix") {
    val t = new Tester()
      .addPath("./examples/analysis/IFDS.flix")
      .run()
    t.checkSuccess()
  }

  test("IDE.flix") {
    val t = new Tester()
      .addPath("./examples/analysis/IDE.flix")
      .run()
    t.checkSuccess()
  }

  test("SUOpt.flix") {
    val t = new Tester()
      .addPath("./examples/analysis/SUopt.flix")
      .run()
    t.checkSuccess()
  }

  test("FloydWarshall.flix") {
    val t = new Tester()
      .addPath("./examples/misc/FloydWarshall.flix")
      .run()
    t.checkSuccess()
  }

}
