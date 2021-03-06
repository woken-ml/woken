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

package ch.chuv.lren.woken.service

import cats.Monoid
import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import ch.chuv.lren.woken.config.{ DatabaseConfiguration, WokenConfiguration, configurationFailed }
import ch.chuv.lren.woken.core.model.VariablesMeta
import ch.chuv.lren.woken.core.threads
import ch.chuv.lren.woken.dao._
import ch.chuv.lren.woken.messages.datasets.Dataset
import com.typesafe.scalalogging.Logger
import doobie.util.transactor.Transactor
import org.slf4j.LoggerFactory
import sup.data.Tagged
import sup.{ HealthCheck, HealthReporter, mods }

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

case class DatabaseServices[F[_]](
    config: WokenConfiguration,
    featuresService: FeaturesService[F],
    wokenRepository: WokenRepository[F],
    variablesMetaService: VariablesMetaRepository[F],
    queryToJobService: QueryToJobService[F],
    datasetService: DatasetService,
    algorithmLibraryService: AlgorithmLibraryService
)(implicit F: Sync[F]) {

  import DatabaseServices.logger

  def jobResultService: JobResultRepository[F]       = wokenRepository.jobResults
  def resultsCacheService: ResultsCacheRepository[F] = wokenRepository.resultsCache
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def validate(): F[Unit] = {

    val datasetCodes =
      datasetService.datasets().values.filter(_.location.isEmpty).map(_.id.code).mkString(" ")
    logger.info(s"Checking configuration of datasets $datasetCodes...")

    implicit val FPlus: Monoid[F[Unit]] = new Monoid[F[Unit]] {
      def empty: F[Unit]                           = F.unit
      def combine(x: F[Unit], y: F[Unit]): F[Unit] = x.flatMap(_ => y)
    }

    // Validate local datasets
    Monoid
      .combineAll(datasetService.datasets().values.filter(_.location.isEmpty).map { dataset =>
        Monoid
          .combineAll(dataset.tables.map { table =>
            {
              featuresService
                .featuresTable(table)
                .fold[F[Unit]](
                  { error: NonEmptyList[String] =>
                    val errMsg = error.mkString_(",")
                    logger.error(errMsg)
                    F.raiseError(new IllegalStateException(errMsg))
                  }, { table: FeaturesTableService[F] =>
                    validateTable(dataset, table)
                  }
                )
            }
          })
          .map(
            _ => {
              val tables = dataset.tables.map(_.toString).mkString(",")
              logger.info(
                s"Dataset ${dataset.id.code} (${dataset.label}) registered locally on tables $tables"
              )
            }
          )
      })
      .map(_ => logger.info("[OK] Datasets are valid"))

  }

  private def validateTable(dataset: Dataset, table: FeaturesTableService[F]): F[Unit] =
    for {
      _         <- tableShouldContainRowsForDataset(dataset, table)
      variables <- tableShouldHaveMetadataDefined(table)
      _         <- tableFieldsShouldMatchMetadata(table, variables)
    } yield ()

  private def tableShouldContainRowsForDataset(
      dataset: Dataset,
      table: FeaturesTableService[F]
  ): F[Unit] =
    table
      .count(dataset.id)
      .flatMap[Unit] { count =>
        if (count == 0) {
          val error =
            s"Table ${table.table} contains no value for dataset ${dataset.id.code}"
          logger.error(error)
          F.raiseError(new IllegalStateException(error))
        } else ().pure[F]
      }

  private def tableShouldHaveMetadataDefined(table: FeaturesTableService[F]): F[VariablesMeta] = {
    val tableId = table.table.table
    variablesMetaService
      .get(tableId)
      .flatMap(
        metaO =>
          metaO.fold(
            F.raiseError[VariablesMeta](
              new IllegalStateException(
                s"Cannot find metadata for table ${tableId.toString}"
              )
            )
          )(_.pure[F])
      )
  }

  private def tableFieldsShouldMatchMetadata(
      table: FeaturesTableService[F],
      variables: VariablesMeta
  ): F[Unit] =
    table
      .validateFields(variables.allVariables())
      .map { validation =>
        validation.fold(
          err => F.raiseError(new IllegalStateException(err.map(_._2.msg).mkString_(", "))),
          warnings => F.delay(warnings.foreach(w => logger.warn(w.msg)))
        )
      }

  def close(): F[Unit] = F.pure(())

  type TaggedS[H] = Tagged[String, H]

  lazy val featuresCheck: HealthCheck[F, TaggedS] =
    featuresService.healthCheck.through[F, TaggedS](mods.tagWith("Features database"))

  lazy val wokenCheck: HealthCheck[F, TaggedS] =
    jobResultService.healthCheck.through[F, TaggedS](mods.tagWith("Woken jobs database"))

  lazy val metaCheck: HealthCheck[F, TaggedS] =
    variablesMetaService.healthCheck.through[F, TaggedS](mods.tagWith("Metadata database"))

  lazy val healthChecks: HealthReporter[F, NonEmptyList, TaggedS] =
    HealthReporter.fromChecks(featuresCheck, wokenCheck, metaCheck)

}

/**
  * Provides a Resource containing the configured services.
  *
  */
object DatabaseServices {

  private val logger: Logger = Logger(LoggerFactory.getLogger("woken.DatabaseServices"))

  case class Transactors[F[_]](
      featuresTransactor: Transactor[F],
      wokenTransactor: Transactor[F],
      metaTransactor: Transactor[F]
  )

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def resource[F[_]: Effect: ContextShift: Timer](
      config: WokenConfiguration
  ): Resource[F, DatabaseServices[F]] = {

    logger.info("Connect to databases...")

    def transactorFor(
        dbConfig: DatabaseConfiguration,
        connectEC: ExecutionContext,
        transactionEC: ExecutionContext
    ): Resource[F, Transactor[F]] =
      if (dbConfig.poolSize > 0)
        DatabaseConfiguration
          .dbTransactor[F](dbConfig, connectEC, transactionEC)
          .widen[Transactor[F]]
      else
        Resource.pure(
          DatabaseConfiguration
            .simpleDbTransactor[F](dbConfig)
        )

    val transactors: Resource[F, Transactors[F]] = for {
      // our connect EC
      connectEC <- threads.fixedThreadPool[F](size = 7)
      // our transaction EC
      transactionEC      <- threads.cachedThreadPool[F]
      featuresTransactor <- transactorFor(config.featuresDb, connectEC, transactionEC)
      wokenTransactor    <- transactorFor(config.wokenDb, connectEC, transactionEC)
      metaTransactor     <- transactorFor(config.metaDb, connectEC, transactionEC)
    } yield Transactors[F](featuresTransactor, wokenTransactor, metaTransactor)

    transactors.flatMap { t =>
      val datasetService          = ConfBasedDatasetService(config.config, config.jobs)
      val algorithmLibraryService = AlgorithmLibraryService()

      val servicesIO = for {
        wokenService <- mkService(t.wokenTransactor, config.wokenDb) { xa =>
          Sync[F].delay(WokenRepositoryDAO(xa))
        }

        featuresService <- mkService(t.featuresTransactor, config.featuresDb) { xa =>
          FeaturesRepositoryDAO(xa, config.featuresDb, wokenService).map {
            _.map { FeaturesService.apply[F] }
          }
        }.map(_.valueOr(configurationFailed))

        variablesMetaService <- mkService(t.metaTransactor, config.metaDb) { xa =>
          Sync[F].delay(MetadataRepositoryDAO(xa).variablesMeta)
        }

        queryToJobService = QueryToJobService(
          featuresService,
          variablesMetaService,
          config.jobs,
          config.algorithmLookup
        )
      } yield
        DatabaseServices[F](
          config,
          featuresService,
          wokenService,
          variablesMetaService,
          queryToJobService,
          datasetService,
          algorithmLibraryService
        )

      Resource.make(servicesIO.flatMap(service => service.validate().as(service)))(_.close())
    }
  }

  private[this] def mkService[F[_]: Sync, M](
      transactor: Transactor[F],
      dbConfig: DatabaseConfiguration
  )(
      serviceGen: Transactor[F] => F[M]
  ): F[M] =
    for {
      validatedXa <- DatabaseConfiguration
        .validate(transactor, dbConfig)
        .map(_.valueOr(configurationFailed))
      validatedDb <- serviceGen(validatedXa)
      _ <- Sync[F].delay(
        logger.info(s"[OK] Connected to database ${dbConfig.database} on ${dbConfig.jdbcUrl}")
      )
    } yield {
      validatedDb
    }

}
