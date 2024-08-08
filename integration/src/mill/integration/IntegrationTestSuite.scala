package mill.integration

import mill.eval.Evaluator
import mill.resolve.SelectMode
import mill.runner.RunnerState
import os.{Path, Shellable}
import utest._
import collection.mutable
import scala.util.control.NonFatal

object IntegrationTestSuite {
  case class EvalResult(isSuccess: Boolean, out: String, err: String)
}

abstract class IntegrationTestSuite extends TestSuite {
  val scriptSlug: String = sys.env("MILL_INTEGRATION_TEST_SLUG")

  val integrationTestMode: String = sys.env("MILL_INTEGRATION_TEST_MODE")
  assert(Set("local", "fork", "server").contains(integrationTestMode))

  def workspacePath: os.Path =
    os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???))

  def scriptSourcePath: os.Path = os.Path(sys.env("MILL_INTEGRATION_REPO_ROOT"))

  def buildPath: os.SubPath = os.sub / "build.sc"

  def wd: Path = workspacePath / buildPath / os.up

  val debugLog = false

  var runnerState = RunnerState.empty

  def eval(s: Shellable*): Boolean = evalFork(os.Inherit, os.Inherit, s, -1)

  def evalStdout(s: Shellable*): IntegrationTestSuite.EvalResult = {
    evalTimeoutStdout(-1, s: _*)
  }

  def evalTimeoutStdout(timeout: Long, s: Shellable*): IntegrationTestSuite.EvalResult = {
    val output = mutable.Buffer.empty[String]
    val error = mutable.Buffer.empty[String]

    evalTimeoutStdout0(timeout, output, error, s)

  }

  def evalTimeoutStdout0(
      timeout: Long,
      output: mutable.Buffer[String],
      error: mutable.Buffer[String],
      s: Seq[Shellable]
  ): IntegrationTestSuite.EvalResult = {

    val processOutput = os.ProcessOutput.Readlines(s => synchronized(output.append(s)))
    val processError = os.ProcessOutput.Readlines(s => synchronized(error.append(s)))

    val result = evalFork(processOutput, processError, s, timeout)

    IntegrationTestSuite.EvalResult(
      result,
      synchronized(output.mkString("\n")),
      synchronized(error.mkString("\n"))
    )
  }

  // Combines stdout and stderr into a single stream; useful for testing
  // against the combined output and also asserting on ordering
  def evalStdCombined(s: Shellable*): IntegrationTestSuite.EvalResult = {
    val combined = mutable.Buffer.empty[String]
    evalTimeoutStdout0(-1, combined, combined, s)
  }

  val millReleaseFileOpt: Option[Path] =
    Option(System.getenv("MILL_TEST_LAUNCHER")).map(os.Path(_, os.pwd))

  val millTestSuiteEnv: Map[String, String] = Map("MILL_TEST_SUITE" -> this.getClass().toString())

  private def evalFork(
      stdout: os.ProcessOutput,
      stderr: os.ProcessOutput,
      s: Seq[Shellable],
      timeout: Long
  ): Boolean = {
    val serverArgs =
      if (integrationTestMode == "server" || integrationTestMode == "local") Seq()
      else Seq("--no-server")

    val debugArgs = if (debugLog) Seq("--debug") else Seq()

    try {
      os.proc(millReleaseFileOpt.get, serverArgs, debugArgs, s).call(
        cwd = wd,
        stdin = os.Inherit,
        stdout = stdout,
        stderr = stderr,
        env = millTestSuiteEnv,
        timeout = timeout
      )
      true
    } catch {
      case NonFatal(_) => false
    }
  }

  def meta(s: String): String = {
    val Seq((List(selector), _)) =
      mill.resolve.ParseArgs.apply(Seq(s), SelectMode.Separated).getOrElse(???)

    val segments = selector._2.value.flatMap(_.pathSegments)
    os.read(wd / "out" / segments.init / s"${segments.last}.json")
  }

  def metaCached(selector: String): Evaluator.Cached = {
    val data = meta(selector)
    upickle.default.read[Evaluator.Cached](data)
  }

  def metaValue[T: upickle.default.Reader](selector: String): T = {
    val cached = metaCached(selector)
    upickle.default.read[T](cached.value)
  }

  def initWorkspace(): Path = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.

    // somehow os.copy does not properly preserve symlinks
    // os.copy(scriptSourcePath, workspacePath)
    os.proc("cp", "-R", scriptSourcePath, workspacePath).call()

    os.remove.all(workspacePath / "out")
    workspacePath
  }

  def mangleFile(p: os.Path, f: String => String): Unit = os.write.over(p, f(os.read(p)))

  override def utestAfterEach(path: Seq[String]): Unit = {
    runnerState = RunnerState.empty
    if (integrationTestMode == "server") {
      // try to stop the server
      try {
        os.proc(millReleaseFileOpt.get, "shutdown").call(
          cwd = wd,
          stdin = os.Inherit,
          stdout = os.Inherit,
          stderr = os.Inherit,
          env = millTestSuiteEnv
        )
      } catch {
        case NonFatal(e) =>
      }
    }
  }
}
