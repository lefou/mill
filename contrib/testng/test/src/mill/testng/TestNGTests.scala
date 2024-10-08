package mill
package testng

import mill.define.Target
import mill.util.Util.millProjectModule
import mill.scalalib._
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.{TestSuite, Tests, assert, _}

object TestNGTests extends TestSuite {

  object demo extends TestBaseModule with JavaModule {

    object test extends JavaTests {
      def testngClasspath = Task {
        millProjectModule(
          "mill-contrib-testng",
          repositoriesTask(),
          artifactSuffix = ""
        )
      }

      override def runClasspath: T[Seq[PathRef]] =
        Task { super.runClasspath() ++ testngClasspath() }
      override def ivyDeps = Task {
        super.ivyDeps() ++
          Agg(
            ivy"org.testng:testng:6.11",
            ivy"de.tototec:de.tobiasroeser.lambdatest:0.8.0"
          )
      }
      override def testFramework = Task {
        "mill.testng.TestNGFramework"
      }
    }

  }

  val resourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "demo"

  def tests: Tests = Tests {
    test("TestNG") {
      test("demo") - UnitTester(demo, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(demo.test.testFramework)
        assert(
          result.value == "mill.testng.TestNGFramework",
          result.evalCount > 0
        )
      }
      test("Test case lookup from inherited annotations") - UnitTester(demo, resourcePath).scoped {
        eval =>
          val Right(result) = eval.apply(demo.test.test())
          val tres = result.value.asInstanceOf[(String, Seq[mill.testrunner.TestResult])]
          assert(
            tres._2.size == 8
          )
      }
    }
  }
}
