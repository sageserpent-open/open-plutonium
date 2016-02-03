package com.sageserpent.plutonium

import java.time.Instant

import com.sageserpent.americium.{Finite, Unbounded}
import scala.collection.mutable
import scala.reflect.runtime._
import scala.reflect.runtime.universe._


/**
  * Created by Gerard on 10/01/2016.
  */
trait PatchRecorderImplementation extends PatchRecorder {
  // This class makes no pretence at exception safety - it doesn't need to in the context
  // of the client 'WorldReferenceImplementation', which provides exception safety at a higher level.
  self: BestPatchSelection with IdentifiedItemFactory =>
  private var _whenEventPertainedToByLastRecordingTookPlace: Option[Unbounded[Instant]] = None

  private var _allRecordingsAreCaptured = false

  override def whenEventPertainedToByLastRecordingTookPlace: Option[Unbounded[Instant]] = _whenEventPertainedToByLastRecordingTookPlace

  override def allRecordingsAreCaptured: Boolean = _allRecordingsAreCaptured

  override def recordPatchFromChange(when: Unbounded[Instant], patch: AbstractPatch[_ <: Identified]): Unit = {
    _whenEventPertainedToByLastRecordingTookPlace = Some(when)

    val itemState = relevantItemStateFor(patch)

    itemState.submitCandidatePatches()

    itemState.addPatch(when, patch)
  }

  override def recordPatchFromMeasurement(when: Unbounded[Instant], patch: AbstractPatch[_ <: Identified]): Unit = {
    _whenEventPertainedToByLastRecordingTookPlace = Some(when)

    relevantItemStateFor(patch).addPatch(when, patch)
  }

  override def recordAnnihilation[Raw <: Identified : TypeTag](when: Instant, id: Raw#Id): Unit = {
    _whenEventPertainedToByLastRecordingTookPlace = Some(Finite(when))

    idToItemStatesMap.get(id) match {
      case Some(itemStates) =>
        val compatibleItemStates = itemStates filter (_.isCompatibleWith(typeOf[Raw]))

        if (compatibleItemStates.nonEmpty) {
          for (itemState <- compatibleItemStates) {
            itemState.submitCandidatePatches()
          }

          itemStates --= compatibleItemStates

          val sequenceIndex = nextSequenceIndex()

          actionQueue.enqueue((sequenceIndex, Unit => {
            annihilateItemsFor(id, when)
          }, Finite(when)))
        } else throw new RuntimeException(s"Attempt to annihilate item of id: $id that does not exist at: $when.")
      case None => throw new RuntimeException(s"Attempt to annihilate item of id: $id that does not exist at: $when.")
    }
  }

  override def noteThatThereAreNoFollowingRecordings(): Unit = {
    _allRecordingsAreCaptured = true

    for (itemState <- idToItemStatesMap.values.flatten) {
      itemState.submitCandidatePatches()
    }

    idToItemStatesMap.clear()
  }

  override def playPatchesUntil(when: Unbounded[Instant]): Unit = {
    while (actionQueue.nonEmpty && (actionQueue.head match {
      case (_, _, whenForAction) => whenForAction <= when
    })) {
      val (_, actionToBeExecuted, _) = actionQueue.dequeue()
      actionToBeExecuted()
    }
  }

  private type CandidatePatches = mutable.MutableList[(SequenceIndex, AbstractPatch[_ <: Identified], Unbounded[Instant])]

  private class ItemState(var itemType: Type) extends IdentifiedItemFactory {
    def isCompatibleWith(itemType: Type) = this.itemType <:< itemType || itemType <:< this.itemType

    def addPatch(when: Unbounded[Instant], patch: AbstractPatch[_ <: Identified]) = {
      candidatePatches += ((nextSequenceIndex(), patch, when))
      if (patch.itemType <:< itemType) {
        itemType = patch.itemType
      }
    }

    def submitCandidatePatches(): Unit =
      if (candidatePatches.nonEmpty) {
        val bestPatch = self(candidatePatches.map(_._2))

        // The best patch has to be applied as if it occurred when the original
        // patch would have taken place - so it steals the latter's sequence index.
        // TODO: is there a test that demonstrates the need for this? Come to think
        // of it though, I'm not sure if a mutator could legitimately make bitemporal
        // queries of other bitemporal items; the only way an inter-item relationship
        // makes a difference is when a query is executed - and that doesn't care about
        // the precise interleaving of events on related items, only that the correct
        // ones have been applied to each item. So does this mean that the action queue
        // can be split across items?
        val (sequenceIndex, _, when) = candidatePatches.head

        actionQueue.enqueue((sequenceIndex, Unit => {
          bestPatch(this)
        }, when))

        candidatePatches.clear()
      }

    private var cachedItem: Option[Any] = None

    private val candidatePatches: CandidatePatches = mutable.MutableList.empty[(SequenceIndex, AbstractPatch[_ <: Identified], Unbounded[Instant])]

    override def itemFor[Raw <: Identified : universe.TypeTag](id: Raw#Id): Raw = {
      cachedItem match {
        case None =>
          val result = PatchRecorderImplementation.this.itemFor(id)
          cachedItem = Some(result)
          result
        case Some(item) =>
          item.asInstanceOf[Raw]
      }
    }

    override def annihilateItemsFor[Raw <: Identified : universe.TypeTag](id: Raw#Id, when: Instant): Unit = PatchRecorderImplementation.this.annihilateItemsFor(id, when)
  }

  private val idToItemStatesMap = mutable.Map.empty[Any, mutable.Set[ItemState]]

  private type SequenceIndex = Long

  private var _nextSequenceIndex: SequenceIndex = 0L

  private type IndexedAction = (SequenceIndex, Unit => Unit, Unbounded[Instant])

  implicit val indexedActionOrdering = Ordering.by[IndexedAction, SequenceIndex](-_._1)

  private val actionQueue = mutable.PriorityQueue[IndexedAction]()


  private def relevantItemStateFor(patch: AbstractPatch[_]) = {
    val itemStates = idToItemStatesMap.getOrElseUpdate(patch.id, mutable.Set.empty)

    val compatibleItemStates = itemStates filter (_.isCompatibleWith(patch.itemType))

    if (compatibleItemStates.nonEmpty) if (1 < compatibleItemStates.size) {
      throw new scala.RuntimeException(s"There is more than one item of id: '${patch.id}' compatible with type '${patch.itemType}', these have types: '${compatibleItemStates map (_.itemType)}'.")
    } else {
      compatibleItemStates.head
    }
    else {
      val itemState = new ItemState(patch.itemType)
      itemStates += itemState
      itemState
    }
  }

  private def nextSequenceIndex() = {
    val result = _nextSequenceIndex
    _nextSequenceIndex += 1
    result
  }

  // TODO - this is for the future...
  def createItemOfType(itemType: Type, id: Any): Any = {
    val clazz = currentMirror.runtimeClass(itemType.typeSymbol.asClass)
    val proxyClassSymbol = currentMirror.classSymbol(clazz)
    val classMirror = currentMirror.reflectClass(proxyClassSymbol.asClass)
    val constructor = itemType.decls.find(_.isConstructor).get
    classMirror.reflectConstructor(constructor.asMethod)(id)
  }
}
