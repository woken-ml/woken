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

package ch.chuv.lren.woken.backends.worker

import akka.actor.{ ActorRef, ActorSystem }
import akka.cluster.pubsub.{ DistributedPubSub, DistributedPubSubMediator }
import akka.pattern.ask
import akka.util.Timeout
import cats.data.NonEmptyList
import cats.effect.{ Effect, IO, LiftIO }
import ch.chuv.lren.woken.messages.validation.{
  ScoringQuery,
  ScoringResult,
  ValidationQuery,
  ValidationResult
}
import com.typesafe.scalalogging.LazyLogging
import sup.{ HealthReporter, mods }
import sup.data.Tagged

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{ higherKinds, postfixOps }

object WokenWorker {
  type TaggedS[H] = Tagged[String, H]
  def apply[F[_]: Effect](system: ActorSystem): WokenWorker[F] = new WokenWorkerImpl(system)
}

import WokenWorker.TaggedS

trait WokenWorker[F[_]] {
  def validate(validationQuery: ValidationQuery): F[ValidationResult]
  def score(scoringQuery: ScoringQuery): F[ScoringResult]
  def healthChecks: HealthReporter[F, NonEmptyList, TaggedS]
}

class WokenWorkerImpl[F[_]: Effect](system: ActorSystem) extends WokenWorker[F] with LazyLogging {

  private val mediator: ActorRef = DistributedPubSub(system).mediator

  override def validate(validationQuery: ValidationQuery): F[ValidationResult] = lift {
    implicit val askTimeout: Timeout = Timeout(5 minutes)
    logger.debug(s"validationQuery: $validationQuery")
    val future = mediator ? DistributedPubSubMediator.Send("/user/validation",
                                                           validationQuery,
                                                           localAffinity = false)
    future.mapTo[ValidationResult]
  }

  override def score(scoringQuery: ScoringQuery): F[ScoringResult] = lift {
    implicit val askTimeout: Timeout = Timeout(5 minutes)
    logger.debug(s"scoringQuery: $scoringQuery")
    val future = mediator ? DistributedPubSubMediator.Send("/user/scoring",
                                                           scoringQuery,
                                                           localAffinity = false)
    future.mapTo[ScoringResult]
  }

  override def healthChecks: HealthReporter[F, NonEmptyList, TaggedS] = {
    val validationServiceCheck =
      DistributedPubSubHealthCheck
        .checkValidation(mediator)
        .through[F, TaggedS](mods.tagWith("Woken validation worker(s)"))
    val scoringServiceCheck =
      DistributedPubSubHealthCheck
        .checkScoring(mediator)
        .through[F, TaggedS](mods.tagWith("Woken scoring worker(s)"))
    HealthReporter.fromChecks(validationServiceCheck, scoringServiceCheck)
  }

  private def lift[R](f: => Future[R]) = implicitly[LiftIO[F]].liftIO(
    IO.fromFuture(IO(f))
  )
}