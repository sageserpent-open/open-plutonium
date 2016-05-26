package com.sageserpent.plutonium

import java.time.Instant
import java.util
import java.util.Optional

import com.sageserpent.americium.Unbounded
import org.scalacheck.{Gen, Prop}
import org.scalatest.prop.Checkers
import org.scalacheck.Prop.BooleanOperators
import org.scalatest.{FlatSpec, Matchers}

import scala.util.Random
import com.sageserpent.americium.randomEnrichment._
import com.sageserpent.plutonium.World.Revision

import scala.collection.mutable.Set

/**
  * Created by Gerard on 13/02/2016.
  */
class WorldStateSharingSupport extends FlatSpec with Matchers with Checkers with WorldSpecSupport {

  /*
  Can create a new world that shares the same histories as a previous one by virtue of using the same Redis data store.

  Can create two worlds side by side that share the same histories by virtue of using the same Redis data store.
  Making new revisions via either world is reflected in the other one.

  Making concurrent revisions via two worlds sharing the same histories is a safe operation:
  the worst that can happen is that an attempt to revise is rendered invalid due to a concurrent
  revision rendering what would have been a consistent set of changes inconsistent with the new history.

  A subtlety: organise the test execution so that some of the instances are forgotten before others are created.
  */

  class DemultiplexingWorld(worldFactory: () => World[Revision], seed: Long) extends World[Int] {
    val random = new scala.util.Random(seed)

    val worlds: Set[World[Int]] = Set.empty

    def world: World[Int] = {
      if (worlds.nonEmpty && random.nextBoolean()) {
        worlds -= random.chooseOneOf(worlds)
      }

      if (worlds.nonEmpty && random.nextBoolean())
        random.chooseOneOf(worlds)
      else {
        val newWorldSharingCommonState = worldFactory()
        worlds += newWorldSharingCommonState
        newWorldSharingCommonState
      }
    }

    override def nextRevision: Revision = world.nextRevision

    override def revise(events: Map[Int, Option[Event]], asOf: Instant): Revision = world.revise(events, asOf)

    override def revise(events: util.Map[Int, Optional[Event]], asOf: Instant): Revision = world.revise(events, asOf)

    override def scopeFor(when: Unbounded[Instant], nextRevision: Revision): Scope = world.scopeFor(when, nextRevision)

    override def scopeFor(when: Unbounded[Instant], asOf: Instant): Scope = world.scopeFor(when, asOf)

    override def forkExperimentalWorld(scope: javaApi.Scope): World[Int] = world.forkExperimentalWorld(scope)

    override def revisionAsOfs: Seq[Instant] = world.revisionAsOfs
  }

  implicit override val generatorDrivenConfig =
    PropertyCheckConfig(maxSize = 30)

  val worldReferenceImplementationSharedState = new MutableState[Int]

  val worldSharingCommonStateFactoryGenerator: Gen[() => World[Int]] =
    Gen.delay {
      new MutableState[Int]
    } map (worldReferenceImplementationSharedState =>
      () => new WorldReferenceImplementation[Int](mutableState = worldReferenceImplementationSharedState))


  behavior of "multiple world instances representing the same world"

  they should "yield the same results to scope queries regardless of which instance is used to define a revision" in {
    val testCaseGenerator = for {
      worldFactory <- worldSharingCommonStateFactoryGenerator
      recordingsGroupedById <- recordingsGroupedByIdGenerator(forbidAnnihilations = false)
      obsoleteRecordingsGroupedById <- nonConflictingRecordingsGroupedByIdGenerator
      seed <- seedGenerator
      random = new Random(seed)
      shuffledRecordings = shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById)
      shuffledObsoleteRecordings = shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, obsoleteRecordingsGroupedById)
      shuffledRecordingAndEventPairs = intersperseObsoleteRecordings(random, shuffledRecordings, shuffledObsoleteRecordings)
      bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffledRecordingAndEventPairs)
      asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
      queryWhen <- unboundedInstantGenerator
    } yield (worldFactory, recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen, seed)
    check(Prop.forAllNoShrink(testCaseGenerator) {
      case (worldFactory, recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen, seed) =>
        val demultiplexingWorld = new DemultiplexingWorld(worldFactory, seed)

        recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, demultiplexingWorld)

        val scope = demultiplexingWorld.scopeFor(queryWhen, demultiplexingWorld.nextRevision)

        val checks = for {RecordingsNoLaterThan(historyId, historiesFrom, pertinentRecordings, _, _) <- recordingsGroupedById flatMap (_.thePartNoLaterThan(queryWhen))
                          Seq(history) = historiesFrom(scope)}
          yield (historyId, history.datums, pertinentRecordings.map(_._1))

        checks.nonEmpty ==>
          Prop.all(checks.map { case (historyId, actualHistory, expectedHistory) => ((actualHistory.length == expectedHistory.length) :| s"${actualHistory.length} == expectedHistory.length") &&
            Prop.all((actualHistory zip expectedHistory zipWithIndex) map { case ((actual, expected), step) => (actual == expected) :| s"For ${historyId}, @step ${step}, ${actual} == ${expected}" }: _*)
          }: _*)
    })
  }

  they should "allow concurrent revisions to be attempted on distinct instances" in {
    pending
  }

}