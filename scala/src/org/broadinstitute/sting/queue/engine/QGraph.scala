package org.broadinstitute.sting.queue.engine

import org.jgrapht.traverse.TopologicalOrderIterator
import org.jgrapht.graph.SimpleDirectedGraph
import scala.collection.JavaConversions
import scala.collection.JavaConversions._
import org.jgrapht.alg.CycleDetector
import org.jgrapht.EdgeFactory
import org.jgrapht.ext.DOTExporter
import java.io.File
import org.jgrapht.event.{TraversalListenerAdapter, EdgeTraversalEvent}
import org.broadinstitute.sting.queue.{QSettings, QException}
import org.broadinstitute.sting.queue.function.{InProcessFunction, CommandLineFunction, QFunction}
import org.broadinstitute.sting.queue.function.scattergather.{CloneFunction, GatherFunction, ScatterGatherableFunction}
import org.broadinstitute.sting.queue.util.{EmailMessage, JobExitException, LsfKillJob, Logging}
import org.apache.commons.lang.StringUtils

/**
 * The internal dependency tracker between sets of function input and output files.
 */
class QGraph extends Logging {
  var dryRun = true
  var bsubAllJobs = false
  var startClean = false
  var dotFile: File = _
  var expandedDotFile: File = _
  var qSettings: QSettings = _
  var debugMode = false
  var statusEmailFrom: String = _
  var statusEmailTo: List[String] = _

  private val jobGraph = newGraph
  private var shuttingDown = false
  private val nl = "%n".format()

  /**
   * Adds a QScript created CommandLineFunction to the graph.
   * @param command Function to add to the graph.
   */
  def add(command: QFunction) {
    try {
      command.qSettings = this.qSettings
      command.freeze
      addEdge(new FunctionEdge(command))
    } catch {
      case e: Exception =>
        throw new QException("Error adding function: " + command, e)
    }
  }

  /**
   * Checks the functions for missing values and the graph for cyclic dependencies and then runs the functions in the graph.
   */
  def run = {
    val numMissingValues = fillGraph
    val isReady = numMissingValues == 0

    if (this.dryRun) {
      dryRunJobs()
    } else if (isReady) {
      logger.info("Running jobs.")
      runJobs()
    }

    if (numMissingValues > 0) {
      logger.error("Total missing values: " + numMissingValues)
    }

    if (isReady && this.dryRun) {
      logger.info("Dry run completed successfully!")
      logger.info("Re-run with \"-run\" to execute the functions.")
    }
  }

  private def fillGraph = {
    logger.info("Generating graph.")
    fill
    if (dotFile != null)
      renderToDot(dotFile)
    var numMissingValues = validate

    if (numMissingValues == 0 && bsubAllJobs) {
      logger.info("Generating scatter gather jobs.")
      val scatterGathers = jobGraph.edgeSet.filter(edge => scatterGatherable(edge))

      var addedFunctions = List.empty[QFunction]
      for (scatterGather <- scatterGathers) {
        val functions = scatterGather.asInstanceOf[FunctionEdge]
                .function.asInstanceOf[ScatterGatherableFunction]
                .generateFunctions()
        if (this.debugMode)
          logger.debug("Scattered into %d parts: %n%s".format(functions.size, functions.mkString(nl)))
        addedFunctions ++= functions
      }

      logger.info("Removing original jobs.")
      this.jobGraph.removeAllEdges(scatterGathers)
      prune

      logger.info("Adding scatter gather jobs.")
      addedFunctions.foreach(this.add(_))

      logger.info("Regenerating graph.")
      fill
      val scatterGatherDotFile = if (expandedDotFile != null) expandedDotFile else dotFile
      if (scatterGatherDotFile != null)
        renderToDot(scatterGatherDotFile)
      numMissingValues = validate
    }

    numMissingValues
  }

  private def scatterGatherable(edge: QEdge) = {
    edge match {
      case functionEdge: FunctionEdge => {
        functionEdge.function match {
          case scatterGather: ScatterGatherableFunction if (scatterGather.scatterGatherable) => true
          case _ => false
        }
      }
      case _ => false
    }
  }

  def checkStatus = {
    // build up the full DAG with scatter-gather jobs
    fillGraph
    logger.info("Checking pipeline status.")
    logStatus
  }

  /**
   * Walks up the graph looking for the previous command line edges.
   * @param function Function to examine for a previous command line job.
   * @param qGraph The graph that contains the jobs.
   * @return A list of prior jobs.
   */
  private def previousFunctions(edge: QEdge): List[FunctionEdge] = {
    var previous = List.empty[FunctionEdge]

    val source = this.jobGraph.getEdgeSource(edge)
    for (incomingEdge <- this.jobGraph.incomingEdgesOf(source)) {
      incomingEdge match {

        // Stop recursing when we find a job along the edge and return its job id
        case functionEdge: FunctionEdge => previous :+= functionEdge

        // For any other type of edge find the jobs preceding the edge
        case edge: QEdge => previous ++= previousFunctions(edge)
      }
    }
    previous
  }

  /**
   * Fills in the graph using mapping functions, then removes out of date
   * jobs, then cleans up mapping functions and nodes that aren't need.
   */
  private def fill = {
    fillIn
    prune
  }

  /**
   * Looks through functions with multiple inputs and outputs and adds mapping functions for single inputs and outputs.
   */
  private def fillIn = {
    // clone since edgeSet is backed by the graph
    JavaConversions.asSet(jobGraph.edgeSet).clone.foreach {
      case cmd: FunctionEdge => {
        addCollectionOutputs(cmd.outputs)
        addCollectionInputs(cmd.inputs)
      }
      case map: MappingEdge => /* do nothing for mapping edges */
    }
  }

  private def getReadyJobs = {
    jobGraph.edgeSet.filter{
      case f: FunctionEdge =>
        this.previousFunctions(f).forall(_.status == RunnerStatus.DONE) && f.status == RunnerStatus.PENDING
      case _ => false
    }.map(_.asInstanceOf[FunctionEdge]).toList.sortWith(compare(_,_))
  }

  private def getRunningJobs = {
    jobGraph.edgeSet.filter{
      case f: FunctionEdge => f.status == RunnerStatus.RUNNING
      case _ => false
    }.map(_.asInstanceOf[FunctionEdge]).toList.sortWith(compare(_,_))
  }

  /**
   *  Removes mapping edges that aren't being used, and nodes that don't belong to anything.
   */
  private def prune = {
    var pruning = true
    while (pruning) {
      pruning = false
      val filler = jobGraph.edgeSet.filter(isFiller(_))
      if (filler.size > 0) {
        jobGraph.removeAllEdges(filler)
        pruning = true
      }
    }

    jobGraph.removeAllVertices(jobGraph.vertexSet.filter(isOrphan(_)))
  }

  /**
   * Validates that the functions in the graph have no missing values and that there are no cycles.
   * @return Number of missing values.
   */
  private def validate = {
    var numMissingValues = 0
    JavaConversions.asSet(jobGraph.edgeSet).foreach {
      case cmd: FunctionEdge =>
        val missingFieldValues = cmd.function.missingFields
        if (missingFieldValues.size > 0) {
          numMissingValues += missingFieldValues.size
          logger.error("Missing %s values for function: %s".format(missingFieldValues.size, cmd.function.description))
          for (missing <- missingFieldValues)
            logger.error("  " + missing)
        }
      case map: MappingEdge => /* do nothing for mapping edges */
    }

    val detector = new CycleDetector(jobGraph)
    if (detector.detectCycles) {
      logger.error("Cycles were detected in the graph:")
      for (cycle <- detector.findCycles)
        logger.error("  " + cycle)
      throw new QException("Cycles were detected in the graph.")
    }

    numMissingValues
  }

  /**
   * Dry-runs the jobs by traversing the graph.
   */
  private def dryRunJobs() = {
    traverseFunctions(edge => {
      edge.function match {
        case qFunction => {
          if (logger.isDebugEnabled) {
            logger.debug(qFunction.commandDirectory + " > " + qFunction.description)
          } else {
            logger.info(qFunction.description)
          }
          logger.info("Output written to " + qFunction.jobOutputFile)
          if (qFunction.jobErrorFile != null) {
            logger.info("Errors written to " + qFunction.jobErrorFile)
          } else {
            if (logger.isDebugEnabled)
              logger.info("Errors also written to " + qFunction.jobOutputFile)
          }
        }
      }
    })
  }

  /**
   * Runs the jobs by traversing the graph.
   */
  private def runJobs() = {
    try {
      traverseFunctions(edge => {
        if (startClean)
          edge.resetToPending()
        else
          checkDone(edge)
      })

      var readyJobs = getReadyJobs
      var runningJobs = Set.empty[FunctionEdge]
      while (!shuttingDown && readyJobs.size + runningJobs.size > 0) {
        var exitedJobs = List.empty[FunctionEdge]
        var failedJobs = List.empty[FunctionEdge]

        runningJobs.foreach(runner => runner.status match {
          case RunnerStatus.RUNNING => /* do nothing while still running */
          case RunnerStatus.FAILED => exitedJobs :+= runner; failedJobs :+= runner
          case RunnerStatus.DONE => exitedJobs :+= runner
        })
        exitedJobs.foreach(runner => runningJobs -= runner)

        readyJobs.foreach(f => {
          f.runner = newRunner(f.function)
          f.runner.start()
          f.status match {
            case RunnerStatus.RUNNING => runningJobs += f
            case RunnerStatus.FAILED => failedJobs :+= f
            case RunnerStatus.DONE => /* do nothing and move on */
          }
        })

        if (failedJobs.size > 0)
          emailFailedJobs(failedJobs)

        if (readyJobs.size == 0 && runningJobs.size > 0)
          Thread.sleep(30000L)
        readyJobs = getReadyJobs
      }
    } catch {
      case e =>
        logger.error("Uncaught error running jobs.", e)
        throw e
    } finally {
      emailStatus()
    }
  }

  /**
   * Checks if an edge is done or if it's an intermediate edge if it can be skipped.
   * This function may modify previous edges if it discovers that the edge passed in
   * is dependent jobs that were previously marked as skipped.
   * @param edge Edge to check to see if it's done or can be skipped.
   */
  private def checkDone(edge: FunctionEdge) = {
    if (edge.function.isIntermediate) {
      // By default we do not need to run intermediate edges.
      // Mark any intermediate edges as skipped, if they're not already done.
      if (edge.status != RunnerStatus.DONE)
        edge.markAsSkipped()
    } else {
      val previous = this.previousFunctions(edge)
      val isDone = edge.status == RunnerStatus.DONE &&
              previous.forall(edge => edge.status == RunnerStatus.DONE || edge.status == RunnerStatus.SKIPPED)
      if (!isDone) {
        edge.resetToPending()
        resetPreviousSkipped(edge, previous)
      }
    }
  }

  /**
   * From the previous edges, resets any that are marked as skipped to pending.
   * If those that are reset have skipped edges, those skipped edges are recursively also set
   * to pending.
   * @param edge Dependent edge.
   * @param previous Previous edges that provide inputs to edge.
   */
  private def resetPreviousSkipped(edge: FunctionEdge, previous: List[FunctionEdge]): Unit = {
    for (previousEdge <- previous.filter(_.status == RunnerStatus.SKIPPED)) {
      previousEdge.resetToPending()
      resetPreviousSkipped(previousEdge, this.previousFunctions(previousEdge))
    }
  }

  private def newRunner(f: QFunction) = {
    f match {
      case cmd: CommandLineFunction =>
        if (this.bsubAllJobs)
          new LsfJobRunner(cmd)
        else
          new ShellJobRunner(cmd)
      case inProc: InProcessFunction =>
        new InProcessRunner(inProc)
      case _ =>
        throw new QException("Unexpected function: " + f)
    }
  }

  private def emailFailedJobs(failed: List[FunctionEdge]) = {
    if (statusEmailTo.size > 0) {
      val emailMessage = new EmailMessage
      emailMessage.from = statusEmailFrom
      emailMessage.to = statusEmailTo
      emailMessage.subject = "Queue function: Failure"
      addFailedFunctions(emailMessage, failed)
      emailMessage.trySend(qSettings.emailSettings)
    }
  }

  private def emailStatus() = {
    if (statusEmailTo.size > 0) {
      var failed = List.empty[FunctionEdge]
      foreachFunction(edge => {
        if (edge.status == RunnerStatus.FAILED) {
          failed :+= edge
        }
      })

      val emailMessage = new EmailMessage
      emailMessage.from = statusEmailFrom
      emailMessage.to = statusEmailTo
      emailMessage.body = getStatus + nl
      if (failed.size == 0) {
        emailMessage.subject = "Queue run: Success"
      } else {
        emailMessage.subject = "Queue run: Failure"
        addFailedFunctions(emailMessage, failed)
      }
      emailMessage.trySend(qSettings.emailSettings)
    }
  }

  private def addFailedFunctions(emailMessage: EmailMessage, failed: List[FunctionEdge]) = {
    val logs = failed.flatMap(edge => logFiles(edge))

    if (emailMessage.body == null)
      emailMessage.body = ""
    emailMessage.body += """
    |Failed functions:
    |
    |%s
    |
    |Logs:
    |%s%n
    |""".stripMargin.trim.format(
      failed.map(_.function.description).mkString(nl+nl),
      logs.map(_.getAbsolutePath).mkString(nl))

    emailMessage.attachments = logs
  }

  private def logFiles(edge: FunctionEdge) = {
    var failedOutputs = List.empty[File]
    failedOutputs :+= edge.function.jobOutputFile
    if (edge.function.jobErrorFile != null)
      failedOutputs :+= edge.function.jobErrorFile
    failedOutputs.filter(file => file != null && file.exists)
  }

  /**
   * Tracks analysis status.
   */
  private class AnalysisStatus(val analysisName: String) {
    var status = RunnerStatus.PENDING
    var scatter = new ScatterGatherStatus
    var gather = new ScatterGatherStatus
  }

  /**
   * Tracks scatter gather status.
   */
  private class ScatterGatherStatus {
    var total = 0
    var done = 0
    var failed = 0
    var skipped = 0
  }

  /**
   * Logs job statuses by traversing the graph and looking for status-related files
   */
  private def logStatus = {
    doStatus(status => logger.info(status))
  }

  /**
   * Gets job statuses by traversing the graph and looking for status-related files
   */
  private def getStatus = {
    val buffer = new StringBuilder
    doStatus(status => buffer.append(status).append(nl))
    buffer.toString
  }

  /**
   * Gets job statuses by traversing the graph and looking for status-related files
   */
  private def doStatus(statusFunc: String => Unit) = {
    var statuses = List.empty[AnalysisStatus]
    var maxWidth = 0
    foreachFunction(edge => {
      val name = edge.function.analysisName
      if (name != null) {
        updateStatus(statuses.find(_.analysisName == name) match {
          case Some(status) => status
          case None =>
            val status = new AnalysisStatus(name)
            maxWidth = maxWidth max name.length
            statuses :+= status
            status
        }, edge)
      }
    })

    statuses.foreach(status => {
      val sgTotal = status.scatter.total + status.gather.total
      val sgDone = status.scatter.done + status.gather.done
      val sgFailed = status.scatter.failed + status.gather.failed
      val sgSkipped = status.scatter.skipped + status.gather.skipped
      if (sgTotal > 0) {
        var sgStatus = RunnerStatus.PENDING
        if (sgFailed > 0)
          sgStatus = RunnerStatus.FAILED
        else if (sgDone == sgTotal)
          sgStatus = RunnerStatus.DONE
        else if (sgDone + sgSkipped == sgTotal)
          sgStatus = RunnerStatus.SKIPPED
        else if (sgDone > 0)
          sgStatus = RunnerStatus.RUNNING
        status.status = sgStatus
      }

      var info = ("%-" + maxWidth + "s [%s]")
              .format(status.analysisName, StringUtils.center(status.status.toString, 7))
      if (status.scatter.total + status.gather.total > 1) {
        info += formatSGStatus(status.scatter, "s")
        info += formatSGStatus(status.gather, "g")
      }
      statusFunc(info)
    })
  }

  /**
   * Updates a status map with scatter/gather status information (e.g. counts)
   */
  private def updateStatus(stats: AnalysisStatus, edge: FunctionEdge) = {
    if (edge.function.isInstanceOf[GatherFunction]) {
      updateSGStatus(stats.gather, edge)
    } else if (edge.function.isInstanceOf[CloneFunction]) {
      updateSGStatus(stats.scatter, edge)
    } else {
      stats.status = edge.status
    }
  }

  private def updateSGStatus(stats: ScatterGatherStatus, edge: FunctionEdge) = {
    stats.total += 1
    edge.status match {
      case RunnerStatus.DONE => stats.done += 1
      case RunnerStatus.FAILED => stats.failed += 1
      case RunnerStatus.SKIPPED => stats.skipped += 1
      /* can't tell the difference between pending and running right now! */
      case RunnerStatus.PENDING =>
      case RunnerStatus.RUNNING =>
    }
  }

  /**
   * Formats a status into nice strings
   */
  private def formatSGStatus(stats: ScatterGatherStatus, prefix: String) = {
    " %s:%dt/%dd/%df".format(
      prefix, stats.total, stats.done, stats.failed)
  }

  /**
   *   Creates a new graph where if new edges are needed (for cyclic dependency checking) they can be automatically created using a generic MappingFunction.
   * @return A new graph
   */
  private def newGraph = new SimpleDirectedGraph[QNode, QEdge](new EdgeFactory[QNode, QEdge] {
    def createEdge(input: QNode, output: QNode) = new MappingEdge(input.files, output.files)})

  private def addEdge(edge: QEdge) = {
    val inputs = QNode(edge.inputs)
    val outputs = QNode(edge.outputs)
    val newSource = jobGraph.addVertex(inputs)
    val newTarget = jobGraph.addVertex(outputs)
    val removedEdges = jobGraph.removeAllEdges(inputs, outputs)
    val added = jobGraph.addEdge(inputs, outputs, edge)
    if (this.debugMode) {
      logger.debug("Mapped from:   " + inputs)
      logger.debug("Mapped to:     " + outputs)
      logger.debug("Mapped via:    " + edge)
      logger.debug("Removed edges: " + removedEdges)
      logger.debug("New source?:   " + newSource)
      logger.debug("New target?:   " + newTarget)
      logger.debug("")
    }
  }

  /**
   * Checks to see if the set of files has more than one file and if so adds input mappings between the set and the individual files.
   * @param files Set to check.
   */
  private def addCollectionInputs(files: Set[File]): Unit = {
    if (files.size > 1)
      for (file <- files)
        addMappingEdge(Set(file), files)
  }

  /**
   * Checks to see if the set of files has more than one file and if so adds output mappings between the individual files and the set.
   * @param files Set to check.
   */
  private def addCollectionOutputs(files: Set[File]): Unit = {
    if (files.size > 1)
      for (file <- files)
        addMappingEdge(files, Set(file))
  }

  /**
   * Adds a directed graph edge between the input set and the output set if there isn't a direct relationship between the two nodes already.
   * @param input Input set of files.
   * @param output Output set of files.
   */
  private def addMappingEdge(input: Set[File], output: Set[File]) = {
    val hasEdge = input == output ||
            jobGraph.getEdge(QNode(input), QNode(output)) != null ||
            jobGraph.getEdge(QNode(output), QNode(input)) != null
    if (!hasEdge)
      addEdge(new MappingEdge(input, output))
  }

  /**
   * Returns true if the edge is mapping edge that is not needed because it does
   * not direct input or output from a user generated CommandLineFunction.
   * @param edge Edge to check.
   * @return true if the edge is not needed in the graph.
   */
  private def isFiller(edge: QEdge) = {
    if (edge.isInstanceOf[MappingEdge]) {
      if (jobGraph.outgoingEdgesOf(jobGraph.getEdgeTarget(edge)).size == 0)
        true
      else if (jobGraph.incomingEdgesOf(jobGraph.getEdgeSource(edge)).size == 0)
        true
      else false
    } else false
  }

  /**
   * Returns true if the node is not connected to any edges.
   * @param node Node (set of files) to check.
   * @return true if this set of files is not needed in the graph.
   */
  private def isOrphan(node: QNode) =
    (jobGraph.incomingEdgesOf(node).size + jobGraph.outgoingEdgesOf(node).size) == 0

  /**
   * Utility function for running a method over all function edges.
   * @param edgeFunction Function to run for each FunctionEdge.
   */
  private def foreachFunction(f: (FunctionEdge) => Unit) = {
    jobGraph.edgeSet.toList
            .filter(_.isInstanceOf[FunctionEdge])
            .map(_.asInstanceOf[FunctionEdge])
            .sortWith(compare(_,_))
            .foreach(f(_))
  }

  private def compare(f1: FunctionEdge, f2: FunctionEdge): Boolean =
    compare(f1.function, f2.function)

  private def compare(f1: QFunction, f2: QFunction): Boolean = {
    val len1 = f1.addOrder.size
    val len2 = f2.addOrder.size
    val len = len1 min len2
    
    for (i <- 0 until len) {
      val order1 = f1.addOrder(i)
      val order2 = f2.addOrder(i)
      if (order1 < order2)
        return true
      if (order1 > order2)
        return false
    }
    if (len1 < len2)
      return true
    else
      return false
  }

  /**
   * Utility function for running a method over all functions, but traversing the nodes in order of dependency.
   * @param edgeFunction Function to run for each FunctionEdge.
   */
  private def traverseFunctions(f: (FunctionEdge) => Unit) = {
    val iterator = new TopologicalOrderIterator(this.jobGraph)
    iterator.addTraversalListener(new TraversalListenerAdapter[QNode, QEdge] {
      override def edgeTraversed(event: EdgeTraversalEvent[QNode, QEdge]) = {
        event.getEdge match {
          case functionEdge: FunctionEdge => f(functionEdge)
          case map: MappingEdge => /* do nothing for mapping functions */
        }
      }
    })
    iterator.foreach(_ => {})
  }  

  /**
   * Outputs the graph to a .dot file.
   * http://en.wikipedia.org/wiki/DOT_language
   * @param file Path to output the .dot file.
   */
  private def renderToDot(file: java.io.File) = {
    val out = new java.io.FileWriter(file)

    // todo -- we need a nice way to visualize the key pieces of information about commands.  Perhaps a
    // todo -- visualizeString() command, or something that shows inputs / outputs
    val ve = new org.jgrapht.ext.EdgeNameProvider[QEdge] {
      def getEdgeName(function: QEdge) = if (function.dotString == null) "" else function.dotString.replace("\"", "\\\"")
    }

    //val iterator = new TopologicalOrderIterator(qGraph.jobGraph)
    (new DOTExporter(new org.jgrapht.ext.IntegerNameProvider[QNode](), null, ve)).export(out, jobGraph)

    out.close
  }

  /**
   * Returns true if any of the jobs in the graph have a status of failed.
   * @return true if any of the jobs in the graph have a status of failed.
   */
  def hasFailed = {
    !this.dryRun && this.jobGraph.edgeSet.exists(edge => {
      edge.isInstanceOf[FunctionEdge] && edge.asInstanceOf[FunctionEdge].status == RunnerStatus.FAILED
    })
  }

  def logFailed = {
    foreachFunction(edge => {
      if (edge.status == RunnerStatus.FAILED) {
        logger.error("-----")
        logger.error("Failed: " + edge.function.description)
        logger.error("Log: " + edge.function.jobOutputFile.getAbsolutePath)
        if (edge.function.jobErrorFile != null)
          logger.error("Error: " + edge.function.jobErrorFile.getAbsolutePath)
      }
    })
  }

  /**
   * Kills any forked jobs still running.
   */
  def shutdown() {
    shuttingDown = true
    val lsfJobRunners = getRunningJobs.filter(_.runner.isInstanceOf[LsfJobRunner]).map(_.runner.asInstanceOf[LsfJobRunner])
    if (lsfJobRunners.size > 0) {
      for (jobRunners <- lsfJobRunners.filterNot(_.job.bsubJobId == null).grouped(10)) {
        try {
          val bkill = new LsfKillJob(jobRunners.map(_.job))
          logger.info(bkill.command)
          bkill.run()
        } catch {
          case jee: JobExitException =>
            logger.error("Unable to kill all jobs:%n%s".format(jee.getMessage))
          case e =>
            logger.error("Unable to kill jobs.", e)
        }
        try {
          jobRunners.foreach(_.removeTemporaryFiles())
        } catch {
          case e => /* ignore */
        }
      }
    }
  }
}
