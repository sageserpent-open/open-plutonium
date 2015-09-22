package com.sageserpent.plutonium

/**
 * Created by Gerard on 19/07/2015.
 */

import java.time.Instant
import java.time.temporal.ChronoUnit

import com.sageserpent.americium
import com.sageserpent.americium._
import com.sageserpent.americium.randomEnrichment._
import com.sageserpent.americium.seqEnrichment$._
import org.scalacheck.Prop.BooleanOperators
import org.scalacheck.{Gen, Prop}
import org.scalatest.FlatSpec
import org.scalatest.prop.Checkers

import scala.collection.immutable.{::, TreeMap}
import scala.util.Random

class WorldSpec extends FlatSpec with Checkers with WorldSpecSupport {

  class NonExistentIdentified extends Identified {
    override type Id = String
    override val id = fail("If I am not supposed to exist, why is something asking for my id?")
  }

  "A world with no history" should "not contain any identifiables" in {
    val world = new WorldReferenceImplementation()



    val scopeGenerator = for {when <- unboundedInstantGenerator
                              asOf <- instantGenerator} yield world.scopeFor(when = when, asOf = asOf)

    check(Prop.forAllNoShrink(scopeGenerator)((scope: world.Scope) => {
      val exampleBitemporal = Bitemporal.wildcard[NonExistentIdentified]()

      scope.render(exampleBitemporal).isEmpty
    }))
  }

  "A world with no history" should "have no current revision" in {
    val world = new WorldReferenceImplementation()

    World.initialRevision === world.nextRevision
  }

  def mixedDataSamplesForAnIdGenerator(faulty: Boolean = false) = Gen.frequency(Seq(dataSamplesForAnIdGenerator_[FooHistory](dataSampleGenerator1(faulty), fooHistoryIdGenerator),
    dataSamplesForAnIdGenerator_[FooHistory](dataSampleGenerator2(faulty), fooHistoryIdGenerator),
    dataSamplesForAnIdGenerator_[BarHistory](dataSampleGenerator3(faulty), barHistoryIdGenerator),
    dataSamplesForAnIdGenerator_[BarHistory](dataSampleGenerator4(faulty), barHistoryIdGenerator),
    dataSamplesForAnIdGenerator_[BarHistory](dataSampleGenerator5(faulty), barHistoryIdGenerator)) map (1 -> _): _*)

  val dataSamplesForAnIdGenerator = mixedDataSamplesForAnIdGenerator()

  val faultyDataSamplesForAnIdGenerator = mixedDataSamplesForAnIdGenerator(faulty = true)


  val recordingsGroupedByIdGenerator = recordingsGroupedByIdGenerator_(dataSamplesForAnIdGenerator, changeWhenGenerator)

  val faultyRecordingsGroupedByIdGenerator = recordingsGroupedByIdGenerator_(faultyDataSamplesForAnIdGenerator, changeWhenGenerator)

  private def eventWhenFrom(recording: ((Any, Unbounded[Instant], Change), Int)) = recording match {
    case ((_, eventWhen, _), _) => eventWhen
  }

  private val chunksShareTheSameEventWhens: (((Unbounded[Instant], Unbounded[Instant]), Instant), ((americium.Unbounded[Instant], Unbounded[Instant]), Instant)) => Boolean = {
    case (((_, trailingEventWhen), _), ((leadingEventWhen, _), _)) => true
  }

  "A world with history added in order of increasing event time" should "reveal all history up to the 'asOf' limit of a scope made from it" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigHistoryOverLotsOfThingsSortedInEventWhenOrder = random.splitIntoNonEmptyPieces((recordingsGroupedById map (_.recordings) flatMap identity sortBy { case (_, eventWhen, _) => eventWhen }).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigHistoryOverLotsOfThingsSortedInEventWhenOrder.length, instantGenerator) map (_.sorted)
                                 asOfToLatestEventWhenMap = TreeMap(asOfs zip (bigHistoryOverLotsOfThingsSortedInEventWhenOrder map (_.last) map eventWhenFrom): _*)
                                 chunksForRevisions = bigHistoryOverLotsOfThingsSortedInEventWhenOrder map (recordingAndEventIdPairs => eventWhenFrom(recordingAndEventIdPairs.head) -> eventWhenFrom(recordingAndEventIdPairs.last)) zip asOfs
                                 latestAsOfsThatMapUnambiguouslyToEventWhens = chunksForRevisions.groupWhile(chunksShareTheSameEventWhens) map (_.last._2)
                                 queryWhen <- instantGenerator retryUntil (asOfToLatestEventWhenMap(latestAsOfsThatMapUnambiguouslyToEventWhens.head) <= americium.Finite(_))
                                 asOfsIncludingAllEventsNoLaterThanTheQueryWhen = latestAsOfsThatMapUnambiguouslyToEventWhens takeWhile (asOf => asOfToLatestEventWhenMap(asOf) <= Finite(queryWhen))
    } yield (recordingsGroupedById, bigHistoryOverLotsOfThingsSortedInEventWhenOrder, asOfs, queryWhen, asOfToLatestEventWhenMap, asOfsIncludingAllEventsNoLaterThanTheQueryWhen)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (recordingsGroupedById, bigHistoryOverLotsOfThingsSortedInEventWhenOrder, asOfs, queryWhen, asOfToLatestEventWhenMap, asOfsIncludingAllEventsNoLaterThanTheQueryWhen) =>
      val world = new WorldReferenceImplementation()

      recordEventsInWorld(bigHistoryOverLotsOfThingsSortedInEventWhenOrder, asOfs, world)

      assert(asOfsIncludingAllEventsNoLaterThanTheQueryWhen.nonEmpty)

      val checks = for {asOf <- asOfsIncludingAllEventsNoLaterThanTheQueryWhen
                        scope = world.scopeFor(Finite(queryWhen), asOf)
                        eventWhenAlignedWithAsOf = asOfToLatestEventWhenMap(asOf)
                        RecordingsForAnId(historyId, _, historiesFrom, recordings) <- recordingsGroupedById filter (Finite(queryWhen) >= _.whenEarliestChangeHappened) filter (eventWhenAlignedWithAsOf >= _.whenEarliestChangeHappened)
                        pertinentRecordings = recordings takeWhile { case (_, eventWhen, _) => eventWhen <= eventWhenAlignedWithAsOf }
                        Seq(history) = {
                          assert(pertinentRecordings.nonEmpty)
                          historiesFrom(scope)
                        }}
        yield (historyId, history.datums, pertinentRecordings.map(_._1))

      Prop.all(checks.map { case (historyId, actualHistory, expectedHistory) => ((actualHistory.length == expectedHistory.length) :| s"${actualHistory.length} == expectedHistory.length") &&
        Prop.all((actualHistory zip expectedHistory zipWithIndex) map { case ((actual, expected), step) => (actual == expected) :| s"For ${historyId}, @step ${step}, ${actual} == ${expected}" }: _*)
      }: _*)
    })
  }

  "A world revealing no history from a scope with a 'revision' limit" should "reveal the same lack of history from a scope with an 'asOf' limit that comes at or after that revision but before the following revision" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator} yield (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen, random)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen, random) =>
      val world = new WorldReferenceImplementation()

      val revisions = recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      val asOfComingAfterTheLastRevision = asOfs.last.plusSeconds(10L)

      val asOfPairs = asOfs.scanRight((asOfComingAfterTheLastRevision, asOfComingAfterTheLastRevision)) { case (asOf, (laterAsOf, _)) => (asOf, laterAsOf) } init

      val checks = for {((earlierAsOfCorrespondingToRevision, laterAsOfComingNoLaterThanAnySucceedingRevision), revision) <- asOfPairs zip revisions
                        laterAsOfSharingTheSameRevisionAsTheEarlierOne = earlierAsOfCorrespondingToRevision plusSeconds random.chooseAnyNumberFromZeroToOneLessThan(earlierAsOfCorrespondingToRevision.until(laterAsOfComingNoLaterThanAnySucceedingRevision, ChronoUnit.SECONDS))

                        baselineScope = world.scopeFor(queryWhen, earlierAsOfCorrespondingToRevision)

                        scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne = world.scopeFor(queryWhen, laterAsOfSharingTheSameRevisionAsTheEarlierOne)

                        RecordingsForAnId(historyId, _, historiesFrom, _) <- recordingsGroupedById filter (queryWhen < _.whenEarliestChangeHappened)}
        yield (historyId, historiesFrom, baselineScope, scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne)

      Prop.all(checks.map { case (historyId, historiesFrom, baselineScope, scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne) => (historiesFrom(baselineScope).isEmpty && historiesFrom(scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne).isEmpty) :| s"For ${historyId}, neither scope should yield a history."
      }: _*)
    })
  }

  "A world revealing a history from a scope with a 'revision' limit" should "reveal the same history from a scope with an 'asOf' limit that comes at or after that revision but before the following revision" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator} yield (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen, random)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen, random) =>
      val world = new WorldReferenceImplementation()

      val revisions = recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      val asOfComingAfterTheLastRevision = asOfs.last.plusSeconds(10L)

      val asOfPairs = asOfs.scanRight((asOfComingAfterTheLastRevision, asOfComingAfterTheLastRevision)) { case (asOf, (laterAsOf, _)) => (asOf, laterAsOf) } init

      val checks = (for {((earlierAsOfCorrespondingToRevision, laterAsOfComingNoLaterThanAnySucceedingRevision), revision) <- asOfPairs zip revisions
                         laterAsOfSharingTheSameRevisionAsTheEarlierOne = earlierAsOfCorrespondingToRevision plusSeconds random.chooseAnyNumberFromZeroToOneLessThan(earlierAsOfCorrespondingToRevision.until(laterAsOfComingNoLaterThanAnySucceedingRevision, ChronoUnit.SECONDS))

                         baselineScope = world.scopeFor(queryWhen, earlierAsOfCorrespondingToRevision)

                         scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne = world.scopeFor(queryWhen, laterAsOfSharingTheSameRevisionAsTheEarlierOne)

                         RecordingsForAnId(historyId, _, historiesFrom, _) <- recordingsGroupedById filter (queryWhen >= _.whenEarliestChangeHappened) filter (_.historiesFrom(baselineScope).nonEmpty)
                         Seq(baselineHistory) = historiesFrom(baselineScope)
                         Seq(historyUnderTest) = historiesFrom(scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne)}
        yield baselineHistory.datums.zip(historyUnderTest.datums).zipWithIndex map (historyId -> _)) flatMap identity

      Prop.all(checks.map { case (historyId, ((actual, expected), step)) => (actual == expected) :| s"For ${historyId}, @step ${step}, ${actual} == ${expected}" }: _*)
    })
  }

  it should "reveal the same next revision from a scope with an 'asOf' limit that comes at or after that revision but before the following revision" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator} yield (bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen, random)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen, random) =>
      val world = new WorldReferenceImplementation()

      val revisions = recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      val asOfComingAfterTheLastRevision = asOfs.last.plusSeconds(10L)

      val asOfPairs = asOfs.scanRight((asOfComingAfterTheLastRevision, asOfComingAfterTheLastRevision)) { case (asOf, (laterAsOf, _)) => (asOf, laterAsOf) } init

      val checks = for {((earlierAsOfCorrespondingToRevision, laterAsOfComingNoLaterThanAnySucceedingRevision), revision) <- asOfPairs zip revisions
                        laterAsOfSharingTheSameRevisionAsTheEarlierOne = earlierAsOfCorrespondingToRevision plusSeconds random.chooseAnyNumberFromZeroToOneLessThan(earlierAsOfCorrespondingToRevision.until(laterAsOfComingNoLaterThanAnySucceedingRevision, ChronoUnit.SECONDS))

                        baselineScope = world.scopeFor(queryWhen, earlierAsOfCorrespondingToRevision)

                        scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne = world.scopeFor(queryWhen, laterAsOfSharingTheSameRevisionAsTheEarlierOne)

      }
        yield (baselineScope, scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne)

      Prop.all(checks.map { case (baselineScope, scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne) => (baselineScope.nextRevision === scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne.nextRevision) :| s"${baselineScope.nextRevision} === ${scopeForLaterAsOfSharingTheSameRevisionAsTheEarlierOne}.nextRevision" }: _*)
    })
  }

  "A world with history" should "reveal all the history up to the 'when' limit of a scope made from it" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator
    } yield (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen) =>
      val world = new WorldReferenceImplementation()

      recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      val scope = world.scopeFor(queryWhen, world.nextRevision)

      val checks = for {RecordingsForAnId(historyId, _, historiesFrom, recordings) <- recordingsGroupedById filter (queryWhen >= _.whenEarliestChangeHappened)
                        pertinentRecordings = recordings takeWhile { case (_, eventWhen, _) => eventWhen <= queryWhen }
                        Seq(history) = historiesFrom(scope)}
        yield (historyId, history.datums, pertinentRecordings.map(_._1))

      Prop.all(checks.map { case (historyId, actualHistory, expectedHistory) => ((actualHistory.length == expectedHistory.length) :| s"${actualHistory.length} == expectedHistory.length") &&
        Prop.all((actualHistory zip expectedHistory zipWithIndex) map { case ((actual, expected), step) => (actual == expected) :| s"For ${historyId}, @step ${step}, ${actual} == ${expected}" }: _*)
      }: _*)
    })
  }

  it should "tell me about the test case." in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator
    } yield (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen) =>
      val world = new WorldReferenceImplementation()

      recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      val scope = world.scopeFor(queryWhen, world.nextRevision)

      for {RecordingsForAnId(historyId, _, historiesFrom, recordings) <- recordingsGroupedById filter (queryWhen >= _.whenEarliestChangeHappened)} {
        println("Recording:-")
        println(s"History id: '${historyId}', queryWhen: '${queryWhen}'")
        for (recording <- recordings) {
          println(s"Recording: '${recording}'")
        }
      }

      Prop(true)
    })
  }

  it should "allow a raw value to be rendered from a bitemporal if the 'when' limit of the scope includes a relevant event." in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator
    } yield (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen) =>
      val world = new WorldReferenceImplementation()

      recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      val scope = world.scopeFor(queryWhen, world.nextRevision)

      val checks = for {RecordingsForAnId(historyId, _, historiesFrom, _) <- recordingsGroupedById filter (queryWhen >= _.whenEarliestChangeHappened)}
        yield (historiesFrom, historyId)

      Prop.all(checks.map { case (historiesFrom, historyId) => {
        (historiesFrom(scope) match {
          case Seq(_) => true
        }) :| s"Could not find a history for id: ${historyId}."
      }
      }: _*)
    })
  }

  it should "not reveal an item at a query time coming before its first defining event" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator
    } yield (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen) =>
      val world = new WorldReferenceImplementation()

      recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      val scope = world.scopeFor(queryWhen, world.nextRevision)

      val checks = for {RecordingsForAnId(historyId, _, historiesFrom, recordings) <- recordingsGroupedById filter (queryWhen < _.whenEarliestChangeHappened)
                        histories = historiesFrom(scope)}
        yield (historyId, histories)

      Prop.all(checks.map { case (historyId, histories) => histories.isEmpty :| s"For ${historyId}, ${histories}.isEmpty" }: _*)
    })
  }


  it should "have a next revision that reflects the last added revision" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
    } yield (bigShuffledHistoryOverLotsOfThings, asOfs)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (bigShuffledHistoryOverLotsOfThings, asOfs) =>
      val world = new WorldReferenceImplementation()

      val revisions = recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      (1 + revisions.last === world.nextRevision) :| s"1 + ${revisions}.last === ${world.nextRevision}"
    })
  }

  it should "have a version timeline that records the 'asOf' time for each of its revisions" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
    } yield (bigShuffledHistoryOverLotsOfThings, asOfs)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (bigShuffledHistoryOverLotsOfThings, asOfs) =>
      val world = new WorldReferenceImplementation()

      recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      Prop.all(asOfs zip world.revisionAsOfs map { case (asOf, timelineAsOf) => (asOf === timelineAsOf) :| s"${asOf} === ${timelineAsOf}" }: _*)
    })
  }

  it should "have a sorted version timeline" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
    } yield (bigShuffledHistoryOverLotsOfThings, asOfs)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (bigShuffledHistoryOverLotsOfThings, asOfs) =>
      val world = new WorldReferenceImplementation()

      recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      Prop.all(world.revisionAsOfs zip world.revisionAsOfs.tail map { case (first, second) => !first.isAfter(second) :| s"!${first}.isAfter(${second})" }: _*)
    })
  }

  it should "allocate revision numbers sequentially" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
    } yield (bigShuffledHistoryOverLotsOfThings, asOfs)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (bigShuffledHistoryOverLotsOfThings, asOfs) =>
      val world = new WorldReferenceImplementation()

      val revisions = recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      Prop.all((revisions zipWithIndex) map { case (revision, index) => (index === revision) :| s"${index} === ${revision}" }: _*)
    })
  }

  it should "have a next revision number that is the size of its version timeline" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
    } yield (bigShuffledHistoryOverLotsOfThings, asOfs)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (bigShuffledHistoryOverLotsOfThings, asOfs) =>
      val world = new WorldReferenceImplementation()

      recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      (world.nextRevision === world.revisionAsOfs.length) :| s"${world.nextRevision} === ${world.revisionAsOfs}.length"
    })
  }


  it should "not permit the 'asOf' time for a new revision to be less than that of any existing revision." in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted) filter (1 < _.toSet.size) // Make sure we have at least two revisions at different times.
    } yield (bigShuffledHistoryOverLotsOfThings, asOfs, random)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (bigShuffledHistoryOverLotsOfThings, asOfs, random) =>
      val numberOfRevisions = asOfs.length

      val candidateIndicesToStartATranspose = asOfs.zip(asOfs.tail).zipWithIndex filter {
        case ((first, second), index) => first isBefore second
      } map (_._2) toSeq

      val indexOfFirstAsOfBeingTransposed = random.chooseOneOf(candidateIndicesToStartATranspose)

      val asOfsWithIncorrectTransposition = asOfs.splitAt(indexOfFirstAsOfBeingTransposed) match {
        case (asOfsBeforeTransposition, Seq(first, second, asOfsAfterTransposition@_*)) => asOfsBeforeTransposition ++ Seq(second, first) ++ asOfsAfterTransposition
      }

      val world = new WorldReferenceImplementation()

      {
        intercept[IllegalArgumentException](recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfsWithIncorrectTransposition, world))
        true
      } :| s"Using ${asOfsWithIncorrectTransposition} should cause a precondition failure."
    })
  }

  it should "create a scope that captures the arguments passed to 'scopeFor'" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator
    } yield (bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen) =>
      val world = new WorldReferenceImplementation()

      recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, world)

      val asOfBeforeInitialRevision = asOfs.head minusSeconds 10

      def nextRevisions(nextRevisions: List[(Instant, (Int, Int))], asOf: Instant) = nextRevisions match {
        case (preceedingAsOf, (preceedingNextRevision, preceedingNextRevisionAfterFirstDuplicate)) :: tail if asOf == preceedingAsOf => (asOf -> ((1 + preceedingNextRevision) -> preceedingNextRevisionAfterFirstDuplicate)) :: tail
        case (_, (preceedingNextRevision, _)) :: _ => {
          var nextRevision = 1 + preceedingNextRevision
          (asOf -> (nextRevision -> nextRevision)) :: nextRevisions
        }
      }

      val asOfAndNextRevisionPairs = (List(asOfBeforeInitialRevision -> (World.initialRevision -> World.initialRevision)) /: asOfs)(nextRevisions) reverse

      val checksViaAsOf = for {(asOf, (nextRevisionAfterDuplicates, _)) <- asOfAndNextRevisionPairs
                               scopeViaAsOf = world.scopeFor(queryWhen, asOf)
      } yield (asOf, nextRevisionAfterDuplicates, scopeViaAsOf)

      val checksViaNextRevision = for {(asOf, (nextRevisionAfterDuplicates, nextRevisionAfterFirstDuplicate)) <- asOfAndNextRevisionPairs
                                       nextRevision <- nextRevisionAfterFirstDuplicate to nextRevisionAfterDuplicates
                                       scopeViaNextRevision = world.scopeFor(queryWhen, nextRevision)
      } yield (asOf, nextRevision, scopeViaNextRevision)

      Prop.all(checksViaAsOf map { case (asOf, nextRevision, scopeViaAsOf) =>
        (Finite(asOf) === scopeViaAsOf.asOf) :| s"Finite(${asOf}) === scopeViaAsOf.asOf" &&
          (nextRevision === scopeViaAsOf.nextRevision) :| s"${nextRevision} === scopeViaAsOf.nextRevision" &&
          (queryWhen == scopeViaAsOf.when) :| s"${queryWhen} == scopeViaAsOf.when"
      }: _*) && (checksViaNextRevision.head match {
        case (asOf, nextRevision, scopeViaNextRevision) =>
          (NegativeInfinity[Instant] === scopeViaNextRevision.asOf) :| s"NegativeInfinity[Instant] === scopeViaNextRevision.asOf" &&
            (nextRevision === scopeViaNextRevision.nextRevision) :| s"${nextRevision} === scopeViaNextRevision.nextRevision" &&
            (queryWhen === scopeViaNextRevision.when) :| s"${queryWhen} === scopeViaNextRevision.when"
      }) &&
        Prop.all(checksViaNextRevision.tail map { case (asOf, nextRevision, scopeViaNextRevision) =>
          (Finite(asOf) === scopeViaNextRevision.asOf) :| s"Finite(${asOf}) === scopeViaNextRevision.asOf" &&
            (nextRevision === scopeViaNextRevision.nextRevision) :| s"${nextRevision} === scopeViaNextRevision.nextRevision" &&
            (queryWhen === scopeViaNextRevision.when) :| s"${queryWhen} === scopeViaNextRevision.when"
        }: _*)
    })
  }


  it should "create a scope that is a snapshot unaffected by subsequent revisions" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex)
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator
    } yield (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, asOfs, queryWhen) =>
      val world = new WorldReferenceImplementation()

      def historyFrom(scope: world.Scope) = (for (RecordingsForAnId(historyId, _, historiesFrom, _) <- recordingsGroupedById)
        yield historiesFrom(scope) flatMap (_.datums) map (historyId -> _)) flatMap identity

      // What's being tested is the imperative behaviour of 'World' wrt its scopes - so use imperative code.
      val scopeViaRevisionToHistoryMap = scala.collection.mutable.Map.empty[world.Scope, List[(Any, Any)]]
      val scopeViaAsOfToHistoryMap = scala.collection.mutable.Map.empty[world.Scope, List[(Any, Any)]]

      val results = scala.collection.mutable.MutableList.empty[Prop]

      for (revisionAction <- revisionActions(bigShuffledHistoryOverLotsOfThings, asOfs, world)) {
        val revision = revisionAction()

        results += Prop.all(scopeViaRevisionToHistoryMap map { case (scope, history) => (history === historyFrom(scope)) :| s"history === historyFrom(scope)" } toSeq: _*)
        results += Prop.all(scopeViaAsOfToHistoryMap map { case (scope, history) => (history === historyFrom(scope)) :| s"history === historyFrom(scope)" } toSeq: _*)

        val scopeViaRevision = world.scopeFor(queryWhen, revision)
        scopeViaRevisionToHistoryMap += (scopeViaRevision -> historyFrom(scopeViaRevision))
        val scopeViaAsOf = world.scopeFor(queryWhen, world.revisionAsOfs(revision))
        scopeViaAsOfToHistoryMap += (scopeViaAsOf -> historyFrom(scopeViaAsOf))
      }

      Prop.all(results: _*)
    })
  }

  private def historyFrom(world: World, recordingsGroupedById: List[RecordingsForAnId])(scope: world.Scope): List[(Any, Any)] = (for (RecordingsForAnId(historyId, _, historiesFrom, _) <- recordingsGroupedById)
    yield historiesFrom(scope) flatMap (_.datums) map (historyId -> _)) flatMap identity

  it should "create revisions with the strong exception-safety guarantee" in {
    // TODO - should mix in some 'good' changes into the faulty revisions - currently this test makes its faulty revisions entirely with faulty changes.
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 faultyRecordingsGroupedById <- faultyRecordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 bigShuffledHistoryOverLotsOfThings = (random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity)
                                   .zipWithIndex)).force
                                 bigShuffledFaultyHistoryOverLotsOfThings = (random.splitIntoNonEmptyPieces(shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, faultyRecordingsGroupedById map (_.recordings) flatMap identity)
                                   .zipWithIndex map { case (stuff, index) => stuff -> (-1 - index) })).force // Map with event ids over to strictly negative values to avoid collisions with the changes that are expected to work.
                                 asOfs <- Gen.listOfN(bigShuffledHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 faultyAsOfs <- Gen.listOfN(bigShuffledFaultyHistoryOverLotsOfThings.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator
    } yield (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, bigShuffledFaultyHistoryOverLotsOfThings, asOfs, faultyAsOfs, queryWhen)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (recordingsGroupedById, bigShuffledHistoryOverLotsOfThings, bigShuffledFaultyHistoryOverLotsOfThings, asOfs, faultyAsOfs, queryWhen) =>
      val (mergedShuffledHistoryOverLotsOfThings, mergedAsOfs) = ((bigShuffledHistoryOverLotsOfThings zip asOfs) ++ (bigShuffledFaultyHistoryOverLotsOfThings zip bigShuffledHistoryOverLotsOfThings map { case (faulty, ok) => faulty ++ ok } zip faultyAsOfs) groupBy (_._2)).toSeq sortBy (_._1) flatMap (_._2) unzip

      val utopia = new WorldReferenceImplementation()
      val distopia = new WorldReferenceImplementation()

      recordEventsInWorld(bigShuffledHistoryOverLotsOfThings, asOfs, utopia)
      recordEventsInWorldWithoutGivingUpOnFailure(mergedShuffledHistoryOverLotsOfThings.toStream, mergedAsOfs.toList, distopia)

      assert(utopia.nextRevision == distopia.nextRevision)
      assert(utopia.revisionAsOfs == distopia.revisionAsOfs)

      val utopianScope = utopia.scopeFor(queryWhen, utopia.nextRevision)
      val distopianScope = distopia.scopeFor(queryWhen, distopia.nextRevision)

      val utopianHistory = historyFrom(utopia, recordingsGroupedById)(utopianScope)
      val distopianHistory = historyFrom(distopia, recordingsGroupedById)(distopianScope)

      ((utopianHistory.length == distopianHistory.length) :| s"${utopianHistory.length} == distopianHistory.length") && Prop.all(utopianHistory zip distopianHistory map { case (utopianCase, distopianCase) => (utopianCase === distopianCase) :| s"${utopianCase} === distopianCase" }: _*)
    })
  }

  it should "yield the same histories for scopes including all changes at the latest revision, regardless of how changes are grouped into revisions" in {
    val testCaseGenerator = for {recordingsGroupedById <- recordingsGroupedByIdGenerator
                                 faultyRecordingsGroupedById <- faultyRecordingsGroupedByIdGenerator
                                 seed <- seedGenerator
                                 random = new Random(seed)
                                 shuffledRecordings = (shuffleRecordingsPreservingRelativeOrderOfEventsAtTheSameWhen(random, recordingsGroupedById map (_.recordings) flatMap identity).zipWithIndex).toList
                                 bigShuffledHistoryOverLotsOfThingsOneWay = (random.splitIntoNonEmptyPieces(shuffledRecordings)).force
                                 bigShuffledHistoryOverLotsOfThingsAnotherWay = (random.splitIntoNonEmptyPieces(shuffledRecordings)).force
                                 asOfsOneWay <- Gen.listOfN(bigShuffledHistoryOverLotsOfThingsOneWay.length, instantGenerator) map (_.sorted)
                                 asOfsAnotherWay <- Gen.listOfN(bigShuffledHistoryOverLotsOfThingsAnotherWay.length, instantGenerator) map (_.sorted)
                                 queryWhen <- unboundedInstantGenerator
    } yield (recordingsGroupedById, bigShuffledHistoryOverLotsOfThingsOneWay, bigShuffledHistoryOverLotsOfThingsAnotherWay, asOfsOneWay, asOfsAnotherWay, queryWhen)
    check(Prop.forAllNoShrink(testCaseGenerator) { case (recordingsGroupedById, bigShuffledHistoryOverLotsOfThingsOneWay, bigShuffledHistoryOverLotsOfThingsAnotherWay, asOfsOneWay, asOfsAnotherWay, queryWhen) =>
      val worldOneWay = new WorldReferenceImplementation()
      val worldAnotherWay = new WorldReferenceImplementation()

      recordEventsInWorld(bigShuffledHistoryOverLotsOfThingsOneWay, asOfsOneWay, worldOneWay)
      recordEventsInWorld(bigShuffledHistoryOverLotsOfThingsAnotherWay, asOfsAnotherWay, worldAnotherWay)

      val scopeOneWay = worldOneWay.scopeFor(queryWhen, worldOneWay.nextRevision)
      val scopeAnotherWay = worldAnotherWay.scopeFor(queryWhen, worldAnotherWay.nextRevision)

      val historyOneWay = historyFrom(worldOneWay, recordingsGroupedById)(scopeOneWay)
      val historyAnotherWay = historyFrom(worldAnotherWay, recordingsGroupedById)(scopeAnotherWay)

      ((historyOneWay.length == historyAnotherWay.length) :| s"${historyOneWay.length} == historyAnotherWay.length") && Prop.all(historyOneWay zip historyAnotherWay map { case (caseOneWay, caseAnotherWay) => (caseOneWay === caseAnotherWay) :| s"${caseOneWay} === caseAnotherWay" }: _*)
    })
  }
}