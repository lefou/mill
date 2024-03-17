package mill.eval

import mill.api.Result.{Aborted, Failing}
import mill.api.Strict.Agg
import mill.api._
import mill.define._
import mill.eval.Evaluator.TaskResult
import mill.util.{ColorLogger, MultiBiMap, PrefixLogger}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable
import scala.concurrent._

/**
 * Core logic of evaluating tasks, without any user-facing helper methods
 */
private[mill] trait EvaluatorCore extends GroupEvaluator {

  def baseLogger: ColorLogger

  /**
   * @param goals The tasks that need to be evaluated
   * @param reporter A function that will accept a module id and provide a listener for build problems in that module
   * @param testReporter Listener for test events like start, finish with success/error
   * @param onlyDeps Evaluate only all dependencies of the given goals, but not the goals itself
   */
  def evaluate(
      goals: Agg[Task[_]],
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      logger: ColorLogger = baseLogger,
      onlyDeps: Boolean = false
  ): Evaluator.Results = {
    println(s"evaluate;goals=${goals};onlyDeps=${onlyDeps}")

    os.makeDir.all(outPath)

    PathRef.validatedPaths.withValue(new PathRef.ValidatedPaths()) {
      val ec =
        if (effectiveThreadCount == 1) ExecutionContexts.RunNow
        else new ExecutionContexts.ThreadPool(effectiveThreadCount)

      def contextLoggerMsg(threadId: Int) =
        if (effectiveThreadCount == 1) ""
        else s"[#${if (effectiveThreadCount > 9) f"$threadId%02d" else threadId}] "

      try evaluate0(goals, logger, reporter, testReporter, ec, contextLoggerMsg, onlyDeps)
      finally ec.close()
    }
  }

  @deprecated("Binary compatibility shim", "Mill 0.11.5")
  def evaluate(
      goals: Agg[Task[_]],
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: ColorLogger
  ): Evaluator.Results = evaluate(goals, reporter, testReporter, logger, onlyDeps = false)

  private def getFailing(
      sortedGroups: MultiBiMap[Terminal, Task[_]],
      results: Map[Task[_], Evaluator.TaskResult[(Val, Int)]]
  ): MultiBiMap.Mutable[Terminal, Failing[Val]] = {
    val failing = new MultiBiMap.Mutable[Terminal, Result.Failing[Val]]
    for ((k, vs) <- sortedGroups.items()) {
      val failures = vs.items.flatMap(results.get).collect {
        case Evaluator.TaskResult(f: Result.Failing[(Val, Int)], _) => f.map(_._1)
      }

      failing.addAll(k, Loose.Agg.from(failures))
    }
    failing
  }

  private def evaluate0(
      goals: Agg[Task[_]],
      logger: ColorLogger,
      reporter: Int => Option[CompileProblemReporter] = _ => Option.empty[CompileProblemReporter],
      testReporter: TestReporter = DummyTestReporter,
      ec: ExecutionContext with AutoCloseable,
      contextLoggerMsg: Int => String,
      onlyDeps: Boolean
  ): Evaluator.Results = {
    implicit val implicitEc = ec

//    def plan(goals: Agg[Task[_]]): (MultiBiMap[Terminal, Task[_]], Strict.Agg[Task[_]]) = {
//      val plan = Plan.plan(goals)
//      println("goals:")
//      pprint.pprintln(goals)
//      if (!onlyDeps) plan
//      else {
//
//        val (sortedGroups0, transitive0) = plan
//        // we need to remove the source goals
//        val sortedGroups = new MultiBiMap.Mutable[Terminal, Task[_]]()
//        val (groupsIn, groupsOut) = sortedGroups0.items().toSeq.partition {
//          case (Terminal.Task(t), _) => !goals.contains(t)
//          case (Terminal.Labelled(t, _), _) => !goals.contains(t)
//          case _ => true
//        }
//        groupsIn.foreach {
//          case (k, vs) => sortedGroups.addAll(k, vs)
//        }
////        println("sortedGroups")
////        pprint.pprintln(sortedGroups.items().toSeq)
//        println(s"removed groups: ${groupsOut.size}")
//        pprint.pprintln(groupsOut)
//
//        val (transitive, removedTransitive) = transitive0.toSeq.partition(t => !goals.contains(t))
//        println(s"removed transitive: ${removedTransitive.size}")
//        pprint.pprintln(removedTransitive)
//
//        // and ensure they are not transitively needed
//        val conflictDep = transitive.toSeq.collectFirst {
//          case t if t.inputs.exists(i => goals.contains(i)) =>
//            goals.find(g => t.inputs.contains(g)).head
//        }
//        if (conflictDep.nonEmpty) {
//          val selfTerminals =
//            conflictDep.map(dep => sortedGroups0.lookupValue(dep)).map(_.render)
//          // one of the requested goals depends one another requested goal,
//          // hence --onlydeps isn't possible
//          throw new MillException(
//            s"Cannot limit evaluation to the dependencies of the given targets (--onlydeps). At least one requested target is also a dependency of the others: ${selfTerminals.head}"
//          )
//        }
//
//        (sortedGroups, transitive)
//      }
//    }

    // How to handle onlyDeps?
    // Simple idea: just execute all targets as usual but skip the requested targets (goals)
    // If there are more than one goal, it can happen that they depend on each other
    // How to handle that?
    // 1. Error out
    // 2. run those who are needed for the others
    // 3. just skip everthing that depends on a goal
    val onlyDepsGoals = if (onlyDeps) goals else Agg.empty[Task[_]]

    os.makeDir.all(outPath)
    val chromeProfileLogger = new ChromeProfileLogger(outPath / "mill-chrome-profile.json")
    val profileLogger = new ProfileLogger(outPath / "mill-profile.json")
    val threadNumberer = new ThreadNumberer()
    val (sortedGroups, transitive) = Plan.plan(goals)

    val interGroupDeps: Map[Terminal, Seq[Terminal]] = findInterGroupDeps(sortedGroups)
    val terminals = sortedGroups.keys().toVector
    val failed = new AtomicBoolean(false)
    val count = new AtomicInteger(1)

    val futures = mutable.Map.empty[Terminal, Future[Option[GroupEvaluator.Results]]]

    // We walk the task graph in topological order and schedule the futures
    // to run asynchronously. During this walk, we store the scheduled futures
    // in a dictionary. When scheduling each future, we are guaranteed that the
    // necessary upstream futures will have already been scheduled and stored,
    // due to the topological order of traversal.
    for (terminal <- terminals) {
      val onlyDepsSkip = onlyDepsGoals.contains(
        terminal match {
          case Terminal.Labelled(t, _) => t
          case Terminal.Task(t) => t
        }
      )

      val deps = interGroupDeps(terminal)
      futures(terminal) = Future.sequence(deps.map(futures)).map { upstreamValues =>
        if (failed.get()) None
        else {
          val upstreamResults = upstreamValues
            .iterator
            .flatMap(_.iterator.flatMap(_.newResults))
            .toMap

          val startTime = System.nanoTime() / 1000
          val threadId = threadNumberer.getThreadId(Thread.currentThread())
          val counterMsg = s"${count.getAndIncrement()}/${terminals.size}"
          val contextLogger = PrefixLogger(
            out = logger,
            context = contextLoggerMsg(threadId),
            tickerContext = GroupEvaluator.dynamicTickerPrefix.value
          )

          if (onlyDepsSkip) {
            // don't run the evaluation but just return a Results with all skipped
//            val res = GroupEvaluator.Results(
//              newResults = sortedGroups.lookupKey(terminal).map(t =>
//                (t, TaskResult(Result.Skipped, recalc = () => Result.Success(-1)))
//              ).toMap,
//              newEvaluated = Seq.empty,
//              cached = false,
//              inputsHash = -1,
//              previousInputsHash = -1
//            )
//            println(res)
          }

          val res =  {

            val res = evaluateGroupCached(
              terminal = terminal,
              group = sortedGroups.lookupKey(terminal),
              results = upstreamResults,
              counterMsg = counterMsg,
              zincProblemReporter = reporter,
              testReporter = testReporter,
              logger = contextLogger
            )

            if (failFast && res.newResults.values.exists(_.result.asSuccess.isEmpty))
              failed.set(true)

            res
          }

          val endTime = System.nanoTime() / 1000

          val duration = endTime - startTime

          chromeProfileLogger.log(
            task = Terminal.printTerm(terminal),
            cat = "job",
            startTime = startTime,
            duration = duration,
            threadId = threadNumberer.getThreadId(Thread.currentThread()),
            cached = res.cached
          )

          profileLogger.log(
            ProfileLogger.Timing(
              terminal.render,
              (duration / 1000).toInt,
              res.cached,
              deps.map(_.render),
              res.inputsHash,
              res.previousInputsHash
            )
          )

          Some(res)
        }
      }
    }

    val finishedOptsMap = terminals
      .map(t => (t, Await.result(futures(t), duration.Duration.Inf)))
      .toMap

    val results0: Vector[(Task[_], TaskResult[(Val, Int)])] = terminals
      .flatMap { t =>
        sortedGroups.lookupKey(t).flatMap { t0 =>
          finishedOptsMap(t) match {
            case None => Some((t0, TaskResult(Aborted, () => Aborted)))
            case Some(res) => res.newResults.get(t0).map(r => (t0, r))
          }
        }
      }

    val results: Map[Task[_], TaskResult[(Val, Int)]] = results0.toMap

    chromeProfileLogger.close()
    profileLogger.close()

    EvaluatorCore.Results(
      goals.indexed.map(results(_).map(_._1).result),
      // result of flatMap may contain non-distinct entries,
      // so we manually clean it up before converting to a `Strict.Agg`
      // see https://github.com/com-lihaoyi/mill/issues/2958
      Strict.Agg.from(
        finishedOptsMap.values.flatMap(_.toSeq.flatMap(_.newEvaluated)).iterator.distinct
      ),
      transitive,
      getFailing(sortedGroups, results),
      results.map { case (k, v) => (k, v.map(_._1)) }
    )
  }

  private def findInterGroupDeps(sortedGroups: MultiBiMap[Terminal, Task[_]])
      : Map[Terminal, Seq[Terminal]] = {
    sortedGroups
      .items()
      .map { case (terminal, group) =>
        terminal -> Seq.from(group)
          .flatMap(_.inputs)
          .filterNot(group.contains)
          .distinct
          .map(sortedGroups.lookupValue)
          .distinct
      }
      .toMap
  }
}

private[mill] object EvaluatorCore {

  case class Results(
      rawValues: Seq[Result[Val]],
      evaluated: Agg[Task[_]],
      transitive: Agg[Task[_]],
      failing: MultiBiMap[Terminal, Result.Failing[Val]],
      results: Map[Task[_], TaskResult[Val]]
  ) extends Evaluator.Results
}
