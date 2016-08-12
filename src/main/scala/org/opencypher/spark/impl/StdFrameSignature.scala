package org.opencypher.spark.impl

import org.apache.spark.sql.types.IntegerType
import org.opencypher.spark.api.{BinaryRepresentation, CypherFrameSignature, EmbeddedRepresentation}

object StdFrameSignature {
  val empty = new StdFrameSignature
}

class StdFrameSignature(private val map: Map[StdField, StdSlot] = Map.empty)
  extends CypherFrameSignature {

  override type Field = StdField
  override type Slot = StdSlot

  override def fields: Seq[StdField] = map.keys.toSeq
  override def slots: Seq[StdSlot] = map.values.toSeq.sortBy(_.ordinal)

  def slotNames: Seq[String] = slots.map(_.sym.name)

  override def field(sym: Symbol): StdField =
    map.keys.find(_.sym == sym).getOrElse(throw new IllegalArgumentException(s"Unknown field: $sym"))

  override def slot(field: StdField): StdSlot =
    map.getOrElse(field, throw new IllegalArgumentException(s"Unknown field: $field"))

  override def slot(sym: Symbol): StdSlot =
    map.collectFirst {
      case (field, slot) if field.sym == sym => slot
    }.getOrElse(throw new IllegalArgumentException(s"Unknown slot: $sym"))

  override def addField(field: StdField)(implicit context: PlanningContext): StdFrameSignature = {
    val entry = field -> StdSlot(context.newSlotSymbol(field), field.cypherType, map.values.size, BinaryRepresentation)
    new StdFrameSignature(map + entry)
  }

  override def addIntegerField(field: StdField)(implicit context: PlanningContext): StdFrameSignature = {
    val entry = field -> StdSlot(context.newSlotSymbol(field), field.cypherType, map.values.size, EmbeddedRepresentation(IntegerType))
    new StdFrameSignature(map + entry)
  }

  override def aliasField(oldField: Symbol, newField: Symbol): (StdField, StdFrameSignature) = {
    var copy: StdField = null
    val newMap = map.map {
      case (f, s) if f.sym == oldField =>
        copy = f.copy(sym = newField)
        copy -> s
      case t => t
    }
    (copy, new StdFrameSignature(newMap))
  }

  override def selectFields(fields: StdField*): (StdFrameSignature, Seq[Slot]) = {
    val thatSet = fields.toSet
    val remainingMap = map collect {
      case (field, slot) if thatSet(field) => field -> slot
    }
    val newOrdinals = remainingMap.values.toSeq.sortBy(_.ordinal).zipWithIndex.toMap
    val newMap = remainingMap map {
      case (field, slot) => field -> slot.copy(ordinal = newOrdinals(slot))
    }
    val retainedOldSlotsSortedByNewOrdinal = newOrdinals.toSeq.sortBy(_._2).map(_._1)
    (new StdFrameSignature(newMap), retainedOldSlotsSortedByNewOrdinal)
  }

  def ++(other: StdFrameSignature): StdFrameSignature = {
    // TODO: Remove var
    var highestSlotOrdinal = map.values.map(_.ordinal).max
    val otherWithUpdatedOrdinals = other.map.map {
      case (f, s) =>
        highestSlotOrdinal = highestSlotOrdinal + 1
        f -> s.copy(ordinal = highestSlotOrdinal)
    }
    new StdFrameSignature(map ++ otherWithUpdatedOrdinals)
  }

  override def toString: String = {
    s"Signature($map)"
  }
}
