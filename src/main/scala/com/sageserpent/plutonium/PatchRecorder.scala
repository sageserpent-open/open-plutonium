package com.sageserpent.plutonium

import java.time.Instant

import com.sageserpent.americium.{Finite, Unbounded}
import com.sageserpent.plutonium.ItemExtensionApi.UniqueItemSpecification

import scalaz.std.option.optionSyntax._
import scala.reflect.runtime.universe._

trait BestPatchSelection {
  def apply[AssociatedData](
      relatedPatches: Seq[(AbstractPatch, AssociatedData)])
    : (AbstractPatch, AssociatedData)
}

trait BestPatchSelectionContracts extends BestPatchSelection {
  abstract override def apply[AssociatedData](
      relatedPatches: Seq[(AbstractPatch, AssociatedData)])
    : (AbstractPatch, AssociatedData) = {
    require(relatedPatches.nonEmpty)
    require(
      1 == (relatedPatches map { case (patch, _) => patch.targetId } distinct).size)
    require((for {
      lhs <- relatedPatches
      rhs <- relatedPatches if lhs != rhs
    } yield AbstractPatch.patchesAreRelated(lhs._1, rhs._1)).forall(identity))
    super.apply(relatedPatches)
  }
}

object PatchRecorder {
  trait UpdateConsumer {
    def captureAnnihilation(eventId: EventId, annihilation: Annihilation): Unit

    def capturePatch(when: Unbounded[Instant],
                     eventId: EventId,
                     patch: AbstractPatch): Unit
  }
}

trait PatchRecorder {
  import PatchRecorder._

  val updateConsumer: UpdateConsumer

  def whenEventPertainedToByLastRecordingTookPlace: Option[Unbounded[Instant]]

  def allRecordingsAreCaptured: Boolean

  def recordPatchFromChange(eventId: EventId,
                            when: Unbounded[Instant],
                            patch: AbstractPatch): Unit

  def recordPatchFromMeasurement(eventId: EventId,
                                 when: Unbounded[Instant],
                                 patch: AbstractPatch): Unit

  def recordAnnihilation(eventId: EventId, annihilation: Annihilation): Unit

  def noteThatThereAreNoFollowingRecordings(): Unit
}

trait PatchRecorderContracts extends PatchRecorder {
  require(whenEventPertainedToByLastRecordingTookPlace.isEmpty)
  require(!allRecordingsAreCaptured)

  abstract override def recordPatchFromChange(eventId: EventId,
                                              when: Unbounded[Instant],
                                              patch: AbstractPatch): Unit = {
    require(
      whenEventPertainedToByLastRecordingTookPlace.cata(some = when >= _,
                                                        none = true))
    require(!allRecordingsAreCaptured)
    val result = super.recordPatchFromChange(eventId, when, patch)
    require(whenEventPertainedToByLastRecordingTookPlace == Some(when))
    result
  }

  abstract override def recordPatchFromMeasurement(
      eventId: EventId,
      when: Unbounded[Instant],
      patch: AbstractPatch): Unit = {
    require(
      whenEventPertainedToByLastRecordingTookPlace.cata(some = when >= _,
                                                        none = true))
    require(!allRecordingsAreCaptured)
    val result = super.recordPatchFromMeasurement(eventId, when, patch)
    require(whenEventPertainedToByLastRecordingTookPlace == Some(when))
    result
  }

  abstract override def recordAnnihilation(eventId: EventId,
                                           annihilation: Annihilation): Unit = {
    require(
      whenEventPertainedToByLastRecordingTookPlace
        .cata(some = annihilation.when >= _, none = true))
    require(!allRecordingsAreCaptured)
    val result = super.recordAnnihilation(eventId, annihilation)
    require(
      whenEventPertainedToByLastRecordingTookPlace.contains(annihilation.when))
    result
  }

  abstract override def noteThatThereAreNoFollowingRecordings(): Unit = {
    require(!allRecordingsAreCaptured)
    val result = super.noteThatThereAreNoFollowingRecordings()
    require(allRecordingsAreCaptured)
    result
  }
}
