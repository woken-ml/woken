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

package ch.chuv.lren.woken.dao

import acolyte.jdbc._
import acolyte.jdbc.RowLists.{ rowList1, rowList2 }
import acolyte.jdbc.Implicits._
import cats.effect.IO
import ch.chuv.lren.woken.core.model.database.TableColumn
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.variables.SqlType
import ch.chuv.lren.woken.Predefined.FeaturesDatabase._

import org.scalatest.{ Matchers, WordSpec }

class FeaturesTableRepositoryDAOTest extends WordSpec with Matchers with DAOTest {

  val sampleTableHandler: ScalaCompositeHandler = AcolyteDSL.handleStatement
    .withQueryDetection("^SELECT ") // regex test from beginning
    .withQueryHandler { e: QueryExecution =>
      e.sql.trim match {

        case """SELECT count(*) FROM "sample_data"""" =>
          rowList1(classOf[Int]) :+ 99

        case """SELECT count(*) FROM "sample_data" WHERE "score_test1" >= 2 AND "cognitive_task2" < 9""" =>
          rowList1(classOf[Int]) :+ 5

        case """SELECT count(*) FROM "sample_data" WHERE "score_test1" < 0""" =>
          rowList1(classOf[Int]) :+ 0

        case """SELECT "college_math" , count(*) FROM "sample_data"  GROUP BY "college_math"""" =>
          (rowList2(classOf[String], classOf[Int])
            :+ ("0", 47) // tuple as row
            :+ ("1", 52))

        case """SELECT "college_math" , count(*) FROM "sample_data" WHERE "score_test1" >= 2 GROUP BY "college_math"""" =>
          (rowList2(classOf[String], classOf[Int])
            :+ ("0", 12) // tuple as row
            :+ ("1", 22))

        case _ => throw new IllegalArgumentException(s"Unhandled $e")
      }
    }

  val cdeTableHandler: ScalaCompositeHandler = AcolyteDSL.handleStatement
    .withQueryDetection("^SELECT ") // regex test from beginning
    .withQueryHandler { e: QueryExecution =>
      e.sql.trim match {

        case """SELECT count(*) FROM "cde_features_a" WHERE "dataset" = ?"""
            if e.parameters == List(DefinedParameter("datasetA", ParameterMetaData.Str)) =>
          rowList1(classOf[Int]) :+ 5

        case """SELECT DISTINCT "dataset" as code FROM "cde_features_a" WHERE "apoe4" = 1""" =>
          rowList1(classOf[String]) :+ "datasetA"

        case _ => throw new IllegalArgumentException(s"Unhandled $e")
      }
    }

  "FeaturesTableRepositoryDAO" should {

    "count all records in the table" in withRepository[FeaturesTableRepositoryDAO[IO]](
      sampleTableHandler,
      xa => new FeaturesTableRepositoryDAO[IO](xa, sampleTable, sampleHeaders)
    ) { dao =>
      dao.count.unsafeRunSync() shouldBe 99
    }

    "count all records matching a dataset for a table without a dataset column" in withRepository[
      FeaturesTableRepositoryDAO[IO]
    ](
      sampleTableHandler,
      xa => new FeaturesTableRepositoryDAO[IO](xa, sampleTable, sampleHeaders)
    ) { dao =>
      dao.count(DatasetId(sampleTable.table.name)).unsafeRunSync() shouldBe 99
      dao.count(DatasetId("other")).unsafeRunSync() shouldBe 0
    }

    "count all records matching a dataset for a table with a dataset column" in withRepository[
      FeaturesTableRepositoryDAO[IO]
    ](
      cdeTableHandler,
      xa => new FeaturesTableRepositoryDAO[IO](xa, cdeTable, cdeHeaders)
    ) { dao =>
      dao.count(DatasetId("datasetA")).unsafeRunSync() shouldBe 5
    }

    "count all records matching a filter" in withRepository[FeaturesTableRepositoryDAO[IO]](
      sampleTableHandler,
      xa => new FeaturesTableRepositoryDAO[IO](xa, sampleTable, sampleHeaders)
    ) { dao =>
      val filter = CompoundFilterRule(
        Condition.and,
        rules = List(
          SingleFilterRule("score_test1",
                           "score_test1",
                           "number",
                           InputType.number,
                           Operator.greaterOrEqual,
                           List("2")),
          SingleFilterRule("cognitive_task2",
                           "cognitive_task2",
                           "number",
                           InputType.number,
                           Operator.less,
                           List("9"))
        )
      )
      dao.count(None).unsafeRunSync() shouldBe 99
      dao.count(Some(filter)).unsafeRunSync() shouldBe 5
    }

    "count records grouped by a field" in withRepository[FeaturesTableRepositoryDAO[IO]](
      sampleTableHandler,
      xa => new FeaturesTableRepositoryDAO[IO](xa, sampleTable, sampleHeaders)
    ) { dao =>
      dao.countGroupBy(TableColumn("college_math", SqlType.int), None).unsafeRunSync() shouldBe Map(
        "0" -> 47,
        "1" -> 52
      )

      val filter = SingleFilterRule("score_test1",
                                    "score_test1",
                                    "number",
                                    InputType.number,
                                    Operator.greaterOrEqual,
                                    List("2"))

      dao
        .countGroupBy(TableColumn("college_math", SqlType.int), Some(filter))
        .unsafeRunSync() shouldBe Map("0" -> 12, "1" -> 22)
    }

    "return the default dataset for a table without a dataset column" in withRepository[
      FeaturesTableRepositoryDAO[IO]
    ](
      sampleTableHandler,
      xa => new FeaturesTableRepositoryDAO[IO](xa, sampleTable, sampleHeaders)
    ) { dao =>
      dao.datasets(None).unsafeRunSync() shouldBe Set(DatasetId(sampleTable.table.name))

      val filterAll = SingleFilterRule("score_test1",
                                       "score_test1",
                                       "number",
                                       InputType.number,
                                       Operator.less,
                                       List("0"))

      dao.datasets(Some(filterAll)).unsafeRunSync() shouldBe Set()
    }

    "return the datasets really used for a table with a dataset column" in withRepository[
      FeaturesTableRepositoryDAO[IO]
    ](
      cdeTableHandler,
      xa => new FeaturesTableRepositoryDAO[IO](xa, cdeTable, cdeHeaders)
    ) { dao =>
      val filter =
        SingleFilterRule("apoe4", "apoe4", "number", InputType.number, Operator.equal, List("1"))

      dao.datasets(Some(filter)).unsafeRunSync() shouldBe Set(DatasetId("datasetA"))
    }

  }
}
