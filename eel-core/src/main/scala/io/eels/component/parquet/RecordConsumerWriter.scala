package io.eels.component.parquet

import java.nio.{ByteBuffer, ByteOrder}
import java.time._
import java.time.temporal.ChronoUnit

import com.sksamuel.exts.Logging
import io.eels.coercion.{BigDecimalCoercer, BigIntegerCoercer, BooleanCoercer, DoubleCoercer, FloatCoercer, IntCoercer, LongCoercer, MapCoercer, SequenceCoercer, ShortCoercer, StringCoercer, TimestampCoercer}
import io.eels.schema._
import org.apache.parquet.io.api.{Binary, RecordConsumer}

import scala.math.BigDecimal.RoundingMode.RoundingMode

// accepts a scala/java value and writes it out to a record consumer as
// the appropriate parquet type
trait RecordConsumerWriter {
  def write(record: RecordConsumer, value: Any): Unit
}

object RecordConsumerWriter {
  def apply(dataType: DataType, roundingMode: RoundingMode): RecordConsumerWriter = {
    dataType match {
      case ArrayType(elementType) =>
        new ArrayParquetWriter(RecordConsumerWriter(elementType, roundingMode))
      case BinaryType => BinaryParquetWriter
      case BigIntType => BigIntRecordConsumerWriter
      case BooleanType => BooleanRecordConsumerWriter
      case CharType(_) => StringRecordConsumerWriter
      case DateType => DateRecordConsumerWriter
      case DecimalType(precision, scale) => new DecimalWriter(precision, scale, roundingMode)
      case DoubleType => DoubleRecordConsumerWriter
      case FloatType => FloatRecordConsumerWriter
      case _: IntType => IntRecordConsumerWriter
      case _: LongType => LongRecordConsumerWriter
      case _: ShortType => ShortParquetWriter
      case mapType@MapType(keyType, valueType) =>
        new MapParquetWriter(mapType, apply(keyType, roundingMode), apply(valueType, roundingMode))
      case StringType => StringRecordConsumerWriter
      case struct: StructType => new StructWriter(struct, roundingMode, true)
      case TimeMillisType => TimeRecordConsumerWriter
      case TimestampMillisType => TimestampRecordConsumerWriter
      case VarcharType(_) => StringRecordConsumerWriter
    }
  }
}

class MapParquetWriter(mapType: MapType,
                       keyWriter: RecordConsumerWriter,
                       valueWriter: RecordConsumerWriter) extends RecordConsumerWriter {
  override def write(record: RecordConsumer, value: Any): Unit = {
    val map = MapCoercer.coerce(value)

    record.startGroup()
    record.startField("key_value", 0)

    map.foreach { case (key, v) =>
      record.startGroup()
      record.startField("key", 0)
      keyWriter.write(record, key)
      record.endField("key", 0)

      record.startField("value", 1)
      valueWriter.write(record, v)
      record.endField("value", 1)
      record.endGroup()
    }

    record.endField("key_value", 0)
    record.endGroup()
  }
}

class ArrayParquetWriter(nested: RecordConsumerWriter) extends RecordConsumerWriter with Logging {
  override def write(record: RecordConsumer, value: Any): Unit = {

    val seq = SequenceCoercer.coerce(value)

    record.startGroup()
    record.startField("list", 0)

    seq.foreach { x =>
      record.startGroup()
      record.startField("element", 0)
      nested.write(record, x)
      record.endField("element", 0)
      record.endGroup()
    }

    record.endField("list", 0)
    record.endGroup()
  }
}

class StructWriter(structType: StructType,
                   roundingMode: RoundingMode,
                   nested: Boolean // nested groups, ie not the outer record, must be handled differently
                  ) extends RecordConsumerWriter with Logging {

  val writers = structType.fields.map(_.dataType).map(RecordConsumerWriter.apply(_, roundingMode))

  override def write(record: RecordConsumer, value: Any): Unit = {
    require(record != null)
    if (nested)
      record.startGroup()
    val values = value.asInstanceOf[Seq[Any]]
    for (k <- structType.fields.indices) {
      val value = values(k)
      // if a value is null then parquet requires us to completely skip the field
      if (value != null) {
        val field = structType.field(k)
        record.startField(field.name, k)
        val writer = writers(k)
        writer.write(record, value)
        record.endField(field.name, k)
      }
    }
    if (nested)
      record.endGroup()
  }
}

object BinaryParquetWriter extends RecordConsumerWriter {
  override def write(record: RecordConsumer, value: Any): Unit = {
    value match {
      case array: Array[Byte] => record.addBinary(Binary.fromReusedByteArray(array))
      case seq: Seq[Byte] => write(record, seq.toArray)
    }
  }
}

// The scale stores the number of digits of that value that are to the right of the decimal point,
// and the precision stores the maximum number of sig digits supported in the unscaled value.
class DecimalWriter(precision: Precision, scale: Scale, roundingMode: RoundingMode) extends RecordConsumerWriter {

  private val bits = ParquetSchemaFns.byteSizeForPrecision(precision.value)

  override def write(record: RecordConsumer, value: Any): Unit = {
    val bd = BigDecimalCoercer.coerce(value)
      .setScale(scale.value, roundingMode)
      .underlying()
      .unscaledValue()
    val padded = bd.toByteArray.reverse.padTo(bits, 0: Byte).reverse
    record.addBinary(Binary.fromReusedByteArray(padded))
  }
}

object BigIntRecordConsumerWriter extends RecordConsumerWriter {
  override def write(record: RecordConsumer, value: Any): Unit = {
    record.addLong(BigIntegerCoercer.coerce(value).longValue)
  }
}

object DateRecordConsumerWriter extends RecordConsumerWriter {

  private val UnixEpoch = LocalDate.of(1970, 1, 1)

  // should write out number of days since unix epoch
  override def write(record: RecordConsumer, value: Any): Unit = {
    value match {
      case date: java.sql.Date =>
        val local = Instant.ofEpochMilli(date.getTime).atZone(ZoneId.systemDefault).toLocalDate
        val days = ChronoUnit.DAYS.between(UnixEpoch, local)
        record.addInteger(days.toInt)
    }
  }
}


object TimeRecordConsumerWriter extends RecordConsumerWriter {

  private val JulianEpochInGregorian = LocalDateTime.of(-4713, 11, 24, 0, 0, 0)

  // first 8 bytes are the nanoseconds
  // second 4 bytes are the days
  override def write(record: RecordConsumer, value: Any): Unit = {
    val timestamp = TimestampCoercer.coerce(value)
    val nanos = timestamp.getNanos
    val dt = Instant.ofEpochMilli(timestamp.getTime).atZone(ZoneId.systemDefault)
    val days = ChronoUnit.DAYS.between(JulianEpochInGregorian, dt).toInt
    val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(nanos).putInt(days)
    val binary = Binary.fromReusedByteBuffer(bytes)
    record.addBinary(binary)
  }
}

object TimestampRecordConsumerWriter extends RecordConsumerWriter {

  private val JulianEpochInGregorian = LocalDateTime.of(-4713, 11, 24, 0, 0, 0)

  override def write(record: RecordConsumer, value: Any): Unit = {
    val timestamp = TimestampCoercer.coerce(value)
    val dt = Instant.ofEpochMilli(timestamp.getTime).atZone(ZoneId.systemDefault)
    val days = ChronoUnit.DAYS.between(JulianEpochInGregorian, dt).toInt
    val nanos = timestamp.getNanos + ChronoUnit.NANOS.between(dt.toLocalDate.atStartOfDay(ZoneId.systemDefault).toLocalTime, dt.toLocalTime)
    val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(nanos).putInt(days).array()
    val binary = Binary.fromReusedByteArray(bytes)
    record.addBinary(binary)
  }
}

object StringRecordConsumerWriter extends RecordConsumerWriter {
  override def write(record: RecordConsumer, value: Any): Unit = {
    record.addBinary(Binary.fromString(StringCoercer.coerce(value)))
  }
}

object ShortParquetWriter extends RecordConsumerWriter {
  override def write(record: RecordConsumer, value: Any): Unit = {
    record.addInteger(ShortCoercer.coerce(value))
  }
}

object DoubleRecordConsumerWriter extends RecordConsumerWriter {
  override def write(record: RecordConsumer, value: Any): Unit = {
    record.addDouble(DoubleCoercer.coerce(value))
  }
}

object FloatRecordConsumerWriter extends RecordConsumerWriter {
  override def write(record: RecordConsumer, value: Any): Unit = {
    record.addFloat(FloatCoercer.coerce(value))
  }
}

object BooleanRecordConsumerWriter extends RecordConsumerWriter {
  override def write(record: RecordConsumer, value: Any): Unit = {
    record.addBoolean(BooleanCoercer.coerce(value))
  }
}

object LongRecordConsumerWriter extends RecordConsumerWriter {
  override def write(record: RecordConsumer, value: Any): Unit = {
    record.addLong(LongCoercer.coerce(value))
  }
}

object IntRecordConsumerWriter extends RecordConsumerWriter {
  override def write(record: RecordConsumer, value: Any): Unit = {
    record.addInteger(IntCoercer.coerce(value))
  }
}