package io.eels.component.hive

import java.io.File
import java.nio.file.Paths

import com.sksamuel.exts.io.RecursiveDelete
import io.eels.Row
import io.eels.datastream.DataStream
import io.eels.schema.{Field, StringType, StructType}
import org.scalatest.{FunSuite, Matchers}

import scala.util.{Random, Try}

class HiveTableTest extends FunSuite with HiveConfig with Matchers {

  val dbname = "sam"
  val table = "test_table"

  Try {
    RecursiveDelete(Paths.get("metastore_db"))
  }

  test("partition values should return values for the matching key") {
    assume(new File("/home/sam/development/hadoop-2.7.2/etc/hadoop/core-site.xml").exists)

    HiveTable(dbname, table).drop()

    val schema = StructType(
      Field("a", StringType),
      Field("b", StringType),
      Field("c", StringType)
    )
    def createRow = Row(schema,
      Seq(
        Random.shuffle(List("a", "b", "c")).head,
        Random.shuffle(List("x", "y", "z")).head,
        Random.shuffle(List("q", "r", "s")).head
      )
    )

    val sink = HiveSink(dbname, table).withCreateTable(true, Seq("a", "b"))
    val size = 1000

    DataStream.fromIterator(schema, Iterator.continually(createRow).take(size)).to(sink, 4)

    HiveTable(dbname, table).partitionValues("b") shouldBe Set("x", "y", "z")
  }
}
