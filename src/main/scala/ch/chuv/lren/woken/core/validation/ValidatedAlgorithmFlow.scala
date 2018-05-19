/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.core.validation

import java.time.OffsetDateTime
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorContext
import akka.stream._
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Zip }
import ch.chuv.lren.woken.config.{ AlgorithmDefinition, JobsConfiguration }
import ch.chuv.lren.woken.core.CoordinatorActor
import ch.chuv.lren.woken.core.model._
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.dao.FeaturesDAL
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.validation.{ Score, validationProtocol }
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext

object ValidatedAlgorithmFlow {

  case class Job(jobId: String,
                 inputDb: String,
                 inputTable: String,
                 query: MiningQuery,
                 metadata: List[VariableMetaData],
                 validations: List[ValidationSpec],
                 remoteValidationDatasets: Set[DatasetId],
                 algorithmDefinition: AlgorithmDefinition) {
    // Invariants
    assert(query.algorithm.code == algorithmDefinition.code)

    if (!algorithmDefinition.predictive) {
      assert(validations.isEmpty)
    }
  }

  type ValidationResults = Map[ValidationSpec, Either[String, Score]]

  case class ResultResponse(algorithm: AlgorithmSpec, model: Either[ErrorJobResult, PfaJobResult])

}

case class ValidatedAlgorithmFlow(
    executeJobAsync: CoordinatorActor.ExecuteJobAsync,
    featuresDatabase: FeaturesDAL,
    jobsConf: JobsConfiguration,
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext)
    extends LazyLogging {

  import ValidatedAlgorithmFlow._

  private val crossValidationFlow = CrossValidationFlow(executeJobAsync, featuresDatabase, context)

  /**
    * Run a predictive and local algorithm and perform its validation procedure.
    *
    * If the algorithm is predictive, validate it using cross-validation for validation with local data
    * and if the algorithm is not distributed, validate using remote datasets if any.
    *
    * @param parallelism Parallelism factor
    * @return A flow that executes an algorithm and its validation procedures
    */
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def runLocalAlgorithmAndValidate(
      parallelism: Int
  ): Flow[ValidatedAlgorithmFlow.Job, ResultResponse, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        def mergeValidations(respWithRemoteValidations: (CoordinatorActor.Response,
                                                         ValidationResults),
                             crossValidations: ValidationResults) =
          (respWithRemoteValidations._1, respWithRemoteValidations._2 ++ crossValidations)

        // prepare graph elements
        val broadcast = builder.add(Broadcast[ValidatedAlgorithmFlow.Job](2))
        val zip       = builder.add(Zip[CoordinatorActor.Response, ValidationResults]())
        val response  = builder.add(buildResponse)

        // connect the graph
        broadcast.out(0) ~> runAlgorithmOnLocalData.map(_._2) ~> zip.in0
        broadcast.out(1) ~> crossValidate(parallelism) ~> zip.in1
        zip.out ~> response

        FlowShape(broadcast.in, response.out)
      })
      .named("run-algorithm-and-validate")

  /**
    * Execute an algorithm and learn from the local data.
    *
    * @return
    */
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def runAlgorithmOnLocalData: Flow[ValidatedAlgorithmFlow.Job,
                                    (ValidatedAlgorithmFlow.Job, CoordinatorActor.Response),
                                    NotUsed] =
    Flow[ValidatedAlgorithmFlow.Job]
      .mapAsync(1) { job =>
        val algorithm = job.query.algorithm

        logger.info(s"Start job for algorithm ${algorithm.code}")

        // Spawn a CoordinatorActor
        val jobId = UUID.randomUUID().toString
        val featuresQuery =
          job.query
            .filterNulls(job.algorithmDefinition.variablesCanBeNull,
                         job.algorithmDefinition.covariablesCanBeNull)
            .features(job.inputTable, None)
        val subJob =
          DockerJob(jobId,
                    job.algorithmDefinition.dockerImage,
                    job.inputDb,
                    featuresQuery,
                    job.query.algorithm,
                    job.metadata)
        executeJobAsync(subJob).map(response => (job, response))
      }
      .log("Learned from available local data")
      .named("learn-from-available-local-data")

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private def crossValidate(
      parallelism: Int
  ): Flow[ValidatedAlgorithmFlow.Job, ValidationResults, NotUsed] =
    Flow[ValidatedAlgorithmFlow.Job]
      .map { job =>
        job.validations.map { v =>
          val jobId = UUID.randomUUID().toString
          CrossValidationFlow.Job(jobId,
                                  job.inputDb,
                                  job.inputTable,
                                  job.query,
                                  job.metadata,
                                  v,
                                  job.algorithmDefinition)
        }
      }
      .mapConcat(identity)
      .via(crossValidationFlow.crossValidate(parallelism))
      .map(_.map(t => t._1.validation -> t._2))
      .fold[Map[ValidationSpec, Either[String, Score]]](Map()) { (m, rOpt) =>
        rOpt.fold(m) { r =>
          m + r
        }
      }
      .log("Cross validation results")
      .named("cross-validate")

  private def nodeOf(spec: ValidationSpec): Option[String] =
    spec.parameters.find(_.code == "node").map(_.value)

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private def buildResponse
    : Flow[(CoordinatorActor.Response, ValidationResults), ResultResponse, NotUsed] =
    Flow[(CoordinatorActor.Response, ValidationResults)]
      .map {
        case (response, validations) =>
          val algorithm = response.job.algorithmSpec
          response.results.headOption match {
            case Some(pfa: PfaJobResult) =>
              val model = pfa.copy(validations = pfa.validations ++ validations)
              ResultResponse(algorithm, Right(model))
            case Some(model) =>
              logger.warn(
                s"Expected a PfaJobResult, got $model. All results and validations are discarded"
              )
              val jobResult =
                ErrorJobResult(Some(response.job.jobId),
                               node = jobsConf.node,
                               OffsetDateTime.now(),
                               Some(algorithm.code),
                               s"Expected a PfaJobResult, got ${model.getClass.getName}")
              ResultResponse(algorithm, Left(jobResult))
            case None =>
              val jobResult = ErrorJobResult(Some(response.job.jobId),
                                             node = jobsConf.node,
                                             OffsetDateTime.now(),
                                             Some(algorithm.code),
                                             "No results")
              ResultResponse(algorithm, Left(jobResult))
          }
      }
      .log("Response")
      .named("build-response")
}
