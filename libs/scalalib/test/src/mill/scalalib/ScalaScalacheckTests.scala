package mill.scalalib

import mill.*
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import HelloWorldTests.*
import mill.define.Discover
object ScalaScalacheckTests extends TestSuite {

  object HelloScalacheck extends TestBaseModule {
    object foo extends ScalaModule {
      def scalaVersion = scala212Version
      object test extends ScalaTests {
        override def mvnDeps = Seq(mvn"org.scalacheck::scalacheck:1.13.5")
        override def testFramework = "org.scalacheck.ScalaCheckFramework"
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("scalacheck") - UnitTester(
      HelloScalacheck,
      sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-scalacheck"
    ).scoped { eval =>
      val Right(result) = eval.apply(HelloScalacheck.foo.test.testForked()): @unchecked
      assert(
        result.evalCount > 0,
        result.value._2.map(_.selector) == Seq(
          "String.startsWith",
          "String.endsWith",
          "String.substring",
          "String.substring"
        )
      )
    }
  }
}
