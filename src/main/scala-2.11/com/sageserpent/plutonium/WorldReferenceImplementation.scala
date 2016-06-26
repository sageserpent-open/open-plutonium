package com.sageserpent.plutonium

import java.time.Instant

import com.sageserpent.americium.{PositiveInfinity, Unbounded}

import scala.Ordering.Implicits._
import scala.collection.Searching._
import scala.collection.generic.IsSeqLike
import scala.collection.mutable.MutableList
import scala.collection.{SeqLike, SeqView, mutable}

/**
  * Created by Gerard on 25/05/2016.
  */

object MutableState {

  import World._
  import WorldImplementationCodeFactoring._

  type EventCorrections = MutableList[AbstractEventData]
  type EventIdToEventCorrectionsMap[EventId] = mutable.Map[EventId, EventCorrections]

  def eventCorrectionsPriorToCutoffRevision(eventCorrections: EventCorrections, cutoffRevision: Revision): EventCorrections =
    eventCorrections take numberOfEventCorrectionsPriorToCutoff(eventCorrections, cutoffRevision)

  implicit val isSeqLike = new IsSeqLike[SeqView[Revision, Seq[_]]] {
    type A = Revision
    override val conversion: SeqView[Revision, Seq[_]] => SeqLike[this.A, SeqView[Revision, Seq[_]]] = identity
  }

  def numberOfEventCorrectionsPriorToCutoff(eventCorrections: EventCorrections, cutoffRevision: Revision): EventOrderingTiebreakerIndex = {
    val revisionsView: SeqView[Revision, Seq[_]] = eventCorrections.view.map(_.introducedInRevision)

    revisionsView.search(cutoffRevision) match {
      case Found(foundIndex) => foundIndex
      case InsertionPoint(insertionPoint) => insertionPoint
    }
  }
}

class MutableState[EventId] {

  import MutableState._
  import World._
  import WorldImplementationCodeFactoring._

  var idOfThreadMostRecentlyStartingARevision: Long = -1L

  val eventIdToEventCorrectionsMap: EventIdToEventCorrectionsMap[EventId] = mutable.Map.empty
  val _revisionAsOfs: MutableList[Instant] = MutableList.empty

  def revisionAsOfs: Seq[Instant] = _revisionAsOfs

  def nextRevision: Revision = _revisionAsOfs.size

  type EventIdInclusion = EventId => Boolean

  def pertinentEventDatums(cutoffRevision: Revision, cutoffWhen: Unbounded[Instant], eventIdInclusion: EventIdInclusion): Seq[AbstractEventData] =
    eventIdsAndTheirDatums(cutoffRevision, cutoffWhen, eventIdInclusion)._2

  def eventIdsAndTheirDatums(cutoffRevision: Revision, cutoffWhen: Unbounded[Instant], eventIdInclusion: EventIdInclusion) = {
    val eventIdAndDataPairs = eventIdToEventCorrectionsMap collect {
      case (eventId, eventCorrections) if eventIdInclusion(eventId) =>
        val onePastIndexOfRelevantEventCorrection = numberOfEventCorrectionsPriorToCutoff(eventCorrections, cutoffRevision)
        if (0 < onePastIndexOfRelevantEventCorrection)
          Some(eventId -> eventCorrections(onePastIndexOfRelevantEventCorrection - 1))
        else
          None
    } collect { case Some(idAndDataPair) => idAndDataPair }
    val (eventIds, eventDatums) = eventIdAndDataPairs.unzip

    eventIds -> eventDatums.filterNot(PartialFunction.cond(_) { case eventData: EventData => eventData.serializableEvent.when > cutoffWhen }).toStream
  }

  def pertinentEventDatums(cutoffRevision: Revision, eventIds: Iterable[EventId]): Seq[AbstractEventData] = {
    val eventIdsToBeIncluded = eventIds.toSet
    pertinentEventDatums(cutoffRevision, PositiveInfinity(), eventIdsToBeIncluded.contains)
  }

  def pertinentEventDatums(cutoffRevision: Revision): Seq[AbstractEventData] =
    pertinentEventDatums(cutoffRevision, PositiveInfinity(), _ => true)

  def checkInvariant() = {
    assert(revisionAsOfs zip revisionAsOfs.tail forall { case (first, second) => first <= second })
  }
}


class WorldReferenceImplementation[EventId](mutableState: MutableState[EventId]) extends WorldImplementationCodeFactoring[EventId] {

  import World._
  import WorldImplementationCodeFactoring._

  def this() = this(new MutableState[EventId])

  override def nextRevision: Revision = mutableState.nextRevision

  override def forkExperimentalWorld(scope: javaApi.Scope): World[EventId] = {
    val forkedMutableState = new MutableState[EventId] {
      val baseMutableState = mutableState
      val numberOfRevisionsInCommon = scope.nextRevision
      val cutoffWhenAfterWhichHistoriesDiverge = scope.when

      override def nextRevision: Revision = numberOfRevisionsInCommon + super.nextRevision

      override def revisionAsOfs: Seq[Instant] = (baseMutableState.revisionAsOfs take numberOfRevisionsInCommon) ++ super.revisionAsOfs

      override def pertinentEventDatums(cutoffRevision: Revision, cutoffWhen: Unbounded[Instant], eventIdInclusion: EventIdInclusion): Seq[AbstractEventData] = {
        val cutoffWhenForBaseWorld = cutoffWhen min cutoffWhenAfterWhichHistoriesDiverge
        if (cutoffRevision > numberOfRevisionsInCommon) {
          val (eventIds, eventDatums) = eventIdsAndTheirDatums(cutoffRevision, cutoffWhen, eventIdInclusion)
          val eventIdsToBeExcluded = eventIds.toSet
          eventDatums ++ baseMutableState.pertinentEventDatums(numberOfRevisionsInCommon, cutoffWhenForBaseWorld, eventId => !eventIdsToBeExcluded.contains(eventId) && eventIdInclusion(eventId))
        } else baseMutableState.pertinentEventDatums(cutoffRevision, cutoffWhenForBaseWorld, eventIdInclusion)
      }
    }

    new WorldReferenceImplementation[EventId](forkedMutableState)
  }

  override def revisionAsOfs: Seq[Instant] = mutableState.revisionAsOfs

  override protected def eventTimeline(nextRevision: Revision): Seq[SerializableEvent] = eventTimelineFrom(mutableState.pertinentEventDatums(nextRevision))

  override protected def transactNewRevision(asOf: Instant,
                                             newEventDatumsFor: Revision => Map[EventId, AbstractEventData],
                                             buildAndValidateEventTimelineForProposedNewRevision: (Map[EventId, AbstractEventData], Revision, Seq[AbstractEventData], Set[AbstractEventData]) => Unit): Revision = {
    mutableState.synchronized {
      mutableState.idOfThreadMostRecentlyStartingARevision = Thread.currentThread.getId
      checkRevisionPrecondition(asOf, revisionAsOfs)
    }

    val nextRevisionPriorToUpdate = nextRevision

    val newEventDatums: Map[EventId, AbstractEventData] = newEventDatumsFor(nextRevisionPriorToUpdate)

    val obsoleteEventDatums = Set(mutableState.pertinentEventDatums(nextRevisionPriorToUpdate, newEventDatums.keys): _*)

    val pertinentEventDatumsExcludingTheNewRevision = mutableState.pertinentEventDatums(nextRevisionPriorToUpdate)

    buildAndValidateEventTimelineForProposedNewRevision(newEventDatums, nextRevisionPriorToUpdate, pertinentEventDatumsExcludingTheNewRevision, obsoleteEventDatums)

    mutableState.synchronized {
      if (mutableState.idOfThreadMostRecentlyStartingARevision != Thread.currentThread.getId) {
        throw new RuntimeException("Concurrent revision attempt detected.")
      }

      for ((eventId, eventDatum) <- newEventDatums) {
        mutableState.eventIdToEventCorrectionsMap.getOrElseUpdate(eventId, MutableList.empty) += eventDatum
      }
      mutableState._revisionAsOfs += asOf
      mutableState.checkInvariant()
    }

    nextRevisionPriorToUpdate
  }
}
